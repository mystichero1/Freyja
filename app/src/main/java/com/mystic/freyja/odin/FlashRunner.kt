package com.mystic.freyja.odin

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import java.io.File

/**
 * Drives a full flash session, mirroring Heimdall's FlashAction flow:
 *
 *   claim + handshake -> beginSession -> sendTotalTransferSize ->
 *   (flash PIT / download device PIT) -> flash each selected partition ->
 *   endSession(reboot)
 *
 * Must be run off the main thread ([MainActivity] wraps this in a Thread).
 */
class FlashRunner(
    private val usbManager: UsbManager,
    private val device: UsbDevice,
    private val selectedFiles: Map<String, String>,
    private val onLog: (String) -> Unit,
    private val onProgress: (Int) -> Unit,
    private val noReboot: Boolean = false,
    private val verbose: Boolean = false
) {

    private val partitionOrder = listOf("BOOT", "RECOVERY", "SUPER", "DTBO", "VBMETA")

    fun execute(): Boolean {
        val selected = selectedFiles.filterKeys { partitionOrder.contains(it) }

        if (selected.isEmpty() && !selectedFiles.containsKey(SLOT_PIT)) {
            onLog("Error: No partition files selected to flash.")
            return false
        }

        val connection = usbManager.openDevice(device)
            ?: run {
                onLog("Error: Failed to open USB device.")
                return false
            }

        try {
            val transport = OdinTransport(device, connection)
            if (verbose) {
                transport.onTransportLog = { message -> onLog("    [USB] $message") }
            }

            if (!transport.claimInterface()) {
                onLog("Error: Failed to claim USB interface.")
                return false
            }

            if (!transport.handshake()) {
                onLog("Error: Device handshake failed (expected LOKE).")
                return false
            }
            onLog("Handshake successful.")

            val bridge = BridgeManager(transport, onLog, verbose)

            if (!bridge.beginSession()) return false

            val pitFile = selectedFiles[SLOT_PIT]?.let { File(it) }
            val repartition = pitFile != null

            val pitEntries: List<PitEntry>
            val pitBytes: ByteArray?

            if (repartition) {
                val rawPit = pitFile!!.readBytes()
                pitEntries = PitParser.parse(rawPit)
                    ?: run {
                        onLog("Error: Selected PIT file is not a valid PIT file.")
                        return false
                    }
                pitBytes = PitParser.padToPaddedSize(rawPit)
            } else {
                pitBytes = null
                val devicePit = bridge.downloadPitFile()
                    ?: return false
                pitEntries = PitParser.parse(devicePit)
                    ?: run {
                        onLog("Error: Device PIT file could not be parsed.")
                        return false
                    }
            }

            val totalSize = computeTotalTransferSize(selected, pitFile, repartition)
            if (!bridge.sendTotalTransferSize(totalSize)) return false

            if (repartition) {
                if (!bridge.sendPitFile(pitBytes!!)) return false
                onLog("PIT upload successful.")
            }

            var completedBytes = 0L
            val globalTotal = totalSize.coerceAtLeast(1)

            bridge.setProgressListener { transferred, _ ->
                val overall = ((completedBytes + transferred) * 100 / globalTotal).toInt()
                onProgress(overall.coerceIn(0, 100))
            }

            for (slot in partitionOrder) {
                val path = selected[slot] ?: continue
                val file = File(path)

                val entry = PitParser.findByName(pitEntries, slot)
                if (entry == null) {
                    onLog("Error: Partition \"$slot\" does not exist in the PIT.")
                    return false
                }

                onLog("Uploading $slot (${entry.partitionName})")

                val flashOk = if (entry.binaryType == PIT_BINARY_TYPE_COMMUNICATION_PROCESSOR) {
                    bridge.sendFile(file, OdinProtocol.DESTINATION_MODEM, entry.deviceType)
                } else {
                    bridge.sendFile(file, OdinProtocol.DESTINATION_PHONE, entry.deviceType, entry.identifier)
                }

                if (!flashOk) {
                    onLog("$slot upload failed!")
                    return false
                }

                completedBytes += file.length()
                onLog("$slot upload successful.")
            }

            onLog("All partitions uploaded.")

            if (noReboot) {
                onLog("No-reboot set: device will remain in download mode after flashing.")
            }
            return bridge.endSession(reboot = !noReboot)
        } finally {
            connection.close()
        }
    }

    companion object {
        const val SLOT_PIT = "PIT"

        private const val PIT_BINARY_TYPE_COMMUNICATION_PROCESSOR = 1
    }
}

private fun computeTotalTransferSize(
    files: Map<String, String>,
    pitFile: File?,
    repartition: Boolean
): Long {
    var total = 0L
    for (path in files.values) total += File(path).length()
    if (repartition && pitFile != null) total += pitFile.length()
    return total
}