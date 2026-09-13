package com.mystic.freyja.odin

import java.io.File
import java.io.RandomAccessFile

/**
 * Faithful Kotlin port of Heimdall's BridgeManager.cpp (the Odin / protocol
 * engine). Drives an [OdinTransport] through the begin-session / PIT dump -
 * PIT flash - file transfer - end-session choreography used by FlashAction.
 *
 * All packet framing lives in [OdinProtocol]; transfer pacing, empty-transfer
 * quirks and retry behaviour mirror BridgeManager.cpp SendFile().
 */
class BridgeManager(
    private val transport: OdinTransport,
    private val log: (String) -> Unit = {},
    private val verbose: Boolean = false
) {

    private var fileTransferPacketSize = DEFAULT_PACKET_SIZE
    private var fileTransferSequenceMaxLength = DEFAULT_SEQUENCE_MAX_LENGTH
    private var fileTransferSequenceTimeout = DEFAULT_TIMEOUT_RECEIVE

    private var onProgress: ((bytesTransferred: Long, totalBytes: Long) -> Unit)? = null

    fun setProgressListener(listener: (Long, Long) -> Unit) {
        onProgress = listener
    }

    // --- Session lifecycle -------------------------------------------------

    fun beginSession(): Boolean {
        log("Beginning session...")

        if (!sendPacket(OdinProtocol.sessionPacket(OdinProtocol.SESSION_BEGIN))) {
            log("Failed to begin session!")
            return false
        }

        val deviceDefaultPacketSize = receiveResponse(OdinProtocol.RESPONSE_SESSION_SETUP)
            ?: return false

        if (verbose) log("    deviceDefaultPacketSize: $deviceDefaultPacketSize")

        log("Some devices may take up to 2 minutes to respond. Please be patient!")

        if (deviceDefaultPacketSize != 0) {
            fileTransferSequenceTimeout = 120000
            fileTransferPacketSize = UPGRADED_PACKET_SIZE
            fileTransferSequenceMaxLength = UPGRADED_SEQUENCE_MAX_LENGTH

            if (!sendPacket(
                    OdinProtocol.sessionPacket(OdinProtocol.SESSION_FILE_PART_SIZE, UPGRADED_PACKET_SIZE)
                )) {
                log("Failed to send file part size packet!")
                return false
            }

            val filePartSizeResponse = receiveResponse(OdinProtocol.RESPONSE_SESSION_SETUP)
                ?: return false

            if (verbose) log("    filePartSizeResponse: $filePartSizeResponse")

            if (filePartSizeResponse != 0) {
                log("Unexpected file part size response! Expected: 0, Received: $filePartSizeResponse")
                return false
            }
        }

        log("Session begun.")
        if (verbose) {
            log("    packetSize: $fileTransferPacketSize, sequenceMax: $fileTransferSequenceMaxLength, sequenceTimeout: $fileTransferSequenceTimeout")
        }
        return true
    }

    fun sendTotalTransferSize(totalBytes: Long): Boolean {
        if (totalBytes > Int.MAX_VALUE) {
            log("Total transfer size exceeds supported range: $totalBytes")
            return false
        }

        if (!sendPacket(OdinProtocol.sessionPacket(OdinProtocol.SESSION_TOTAL_BYTES, totalBytes.toInt()))) {
            log("Failed to send total bytes packet!")
            return false
        }

        val result = receiveResponse(OdinProtocol.RESPONSE_SESSION_SETUP) ?: return false

        if (result != 0) {
            log("Unexpected session total bytes response! Expected: 0, Received: $result")
            return false
        }

        return true
    }

    fun endSession(reboot: Boolean): Boolean {
        log("Ending session...")

        if (!sendPacket(OdinProtocol.endSessionPacket(OdinProtocol.END_SESSION))) {
            log("Failed to send end session packet!")
            return false
        }

        if (receiveResponse(OdinProtocol.RESPONSE_END_SESSION) == null) {
            log("Failed to receive session end confirmation!")
            return false
        }

        if (reboot) {
            log("Rebooting device...")

            if (!sendPacket(OdinProtocol.endSessionPacket(OdinProtocol.REBOOT_DEVICE))) {
                log("Failed to send reboot device packet!")
                return false
            }

            if (receiveResponse(OdinProtocol.RESPONSE_END_SESSION) == null) {
                log("Failed to receive reboot confirmation!")
                return false
            }
        }

        return true
    }

    // --- PIT ---------------------------------------------------------------

    /** Downloads the device PIT file. Returns the raw bytes, or null on failure. */
    fun downloadPitFile(): ByteArray? {
        log("Downloading device's PIT file...")

        if (!sendPacket(OdinProtocol.pitPacket(OdinProtocol.PIT_REQUEST_DUMP))) {
            log("Failed to request receival of PIT file!")
            return null
        }

        val fileSize = receiveResponse(OdinProtocol.RESPONSE_PIT_FILE)
            ?: run {
                log("Failed to receive PIT file size!")
                return null
            }

        if (fileSize <= 0) {
            log("Unexpected PIT file size: $fileSize")
            return null
        }

        if (verbose) log("    device PIT file size: $fileSize bytes")

        val transferCount = (fileSize + PIT_CHUNK_SIZE - 1) / PIT_CHUNK_SIZE
        val buffer = ByteArray(fileSize)
        var offset = 0

        for (partIndex in 0 until transferCount) {
            if (!sendPacket(OdinProtocol.dumpPartPitPacket(partIndex))) {
                log("Failed to request PIT file part #$partIndex!")
                return null
            }

            val receiveEmptyTransferAfter = partIndex == transferCount - 1
            val partBuffer = ByteArray(PIT_CHUNK_SIZE)
            val received = transport.receiveRaw(partBuffer, DEFAULT_TIMEOUT_RECEIVE)

            if (received < 0) {
                log("Failed to receive PIT file part #$partIndex!")
                return null
            }

            if (verbose) log("    device PIT file part #$partIndex: $received bytes")

            if (offset + received > buffer.size) {
                log("PIT file download returned too much data!")
                return null
            }

            System.arraycopy(partBuffer, 0, buffer, offset, received)
            offset += received

            if (receiveEmptyTransferAfter) transport.receiveEmpty(DEFAULT_TIMEOUT_EMPTY_TRANSFER)
        }

        if (!sendPacket(OdinProtocol.pitPacket(OdinProtocol.PIT_REQUEST_END_TRANSFER))) {
            log("Failed to send request to end PIT file transfer!")
            return null
        }

        if (receiveResponse(OdinProtocol.RESPONSE_PIT_FILE) == null) {
            log("Failed to receive end PIT file transfer verification!")
            return null
        }

        log("PIT file download successful.")
        return buffer
    }

    /**
     * Sends a PIT file to the device (SendPitData). [paddedPit] must already
     * be padded to the size required by the protocol.
     */
    fun sendPitFile(paddedPit: ByteArray): Boolean {
        log("Uploading PIT")

        if (!sendPacket(OdinProtocol.pitPacket(OdinProtocol.PIT_REQUEST_FLASH))) {
            log("Failed to initialise PIT file transfer!")
            return false
        }

        if (receiveResponse(OdinProtocol.RESPONSE_PIT_FILE) == null) {
            log("Failed to confirm transfer initialisation!")
            return false
        }

        if (!sendPacket(OdinProtocol.pitPacket(OdinProtocol.PIT_REQUEST_PART, paddedPit.size))) {
            log("Failed to send PIT file part information!")
            return false
        }

        if (receiveResponse(OdinProtocol.RESPONSE_PIT_FILE) == null) {
            log("Failed to confirm sending of PIT file part information!")
            return false
        }

        if (!sendPacket(paddedPit)) {
            log("Failed to send file part packet!")
            return false
        }

        if (receiveResponse(OdinProtocol.RESPONSE_PIT_FILE) == null) {
            log("Failed to receive PIT file part response!")
            return false
        }

        if (!sendPacket(OdinProtocol.pitPacket(OdinProtocol.PIT_REQUEST_END_TRANSFER, paddedPit.size))) {
            log("Failed to send end PIT file transfer packet!")
            return false
        }

        if (receiveResponse(OdinProtocol.RESPONSE_PIT_FILE) == null) {
            log("Failed to confirm end of PIT file transfer!")
            return false
        }

        log("PIT upload successful.")
        return true
    }

    // --- File transfer -----------------------------------------------------

    /**
     * Ports BridgeManager::SendFile - the heart of the flash: 30 MiB sequences
     * of [fileTransferPacketSize] parts, with empty-transfer quirks, part-index
     * verification, retry, and the end-of-sequence handshake.
     */
    fun sendFile(
        file: File,
        destination: Int,
        deviceType: Int,
        fileIdentifier: Int = 0xFFFFFFFF.toInt()
    ): Boolean {
        if (destination != OdinProtocol.DESTINATION_PHONE && destination != OdinProtocol.DESTINATION_MODEM) {
            log("Attempted to send file to unknown destination!")
            return false
        }

        if (destination == OdinProtocol.DESTINATION_MODEM && fileIdentifier != 0xFFFFFFFF.toInt()) {
            log("The modem file does not have an identifier!")
            return false
        }

        if (!sendPacket(OdinProtocol.fileTransferFlashStart())) {
            log("Failed to initialise file transfer!")
            return false
        }

        val fileSize = file.length()
        if (fileSize > Int.MAX_VALUE) {
            log("File too large to flash: $fileSize bytes")
            return false
        }

        if (receiveResponse(OdinProtocol.RESPONSE_FILE_TRANSFER) == null) {
            log("Failed to confirm transfer initialisation!")
            return false
        }

        val sequenceByteCount = fileTransferSequenceMaxLength.toLong() * fileTransferPacketSize
        var sequenceCount = fileSize / sequenceByteCount
        var lastSequenceSize = fileTransferSequenceMaxLength
        val partialPacketByteCount = fileSize % fileTransferPacketSize

        if (fileSize % sequenceByteCount != 0L) {
            sequenceCount++
            val lastSequenceBytes = fileSize % sequenceByteCount
            lastSequenceSize = (lastSequenceBytes / fileTransferPacketSize).toInt()
            if (partialPacketByteCount != 0L) lastSequenceSize++
        }

        val raf = RandomAccessFile(file, "r")
        val partBuffer = ByteArray(fileTransferPacketSize)
        var bytesTransferred = 0L
        var previousPercent = -1

        try {
            for (sequenceIndex in 0 until sequenceCount) {
                val isLastSequence = sequenceIndex == sequenceCount - 1
                val sequenceSize = if (isLastSequence) lastSequenceSize else fileTransferSequenceMaxLength
                val sequenceTotalByteCount = sequenceSize * fileTransferPacketSize

                if (!sendPacket(OdinProtocol.fileTransferPartStart(sequenceTotalByteCount))) {
                    log("Failed to begin file transfer sequence!")
                    return false
                }

                if (receiveResponse(OdinProtocol.RESPONSE_FILE_TRANSFER) == null) {
                    log("Failed to confirm beginning of file transfer sequence!")
                    return false
                }

                for (filePartIndex in 0 until sequenceSize) {
                    val emptyTransferFlags =
                        if (filePartIndex == 0) EMPTY_TRANSFER_NONE else EMPTY_TRANSFER_BEFORE

                    var partOk = false
                    var receivedIndex: Int? = null

                    for (attempt in 0..PART_SEND_RETRIES) {
                        partBuffer.fill(0)
                        val bytesRead = raf.read(partBuffer, 0, partBuffer.size)

                        if (!sendPacket(partBuffer, DEFAULT_TIMEOUT_SEND, emptyTransferFlags)) {
                            log("Failed to send file part packet!")
                            break
                        }

                        receivedIndex = receiveResponse(OdinProtocol.RESPONSE_SEND_FILE_PART)
                        if (verbose) log("    file part response: $receivedIndex (expected $filePartIndex)")
                        if (receivedIndex != null && receivedIndex == filePartIndex) {
                            partOk = true
                            break
                        }

                        if (receivedIndex != null && receivedIndex != filePartIndex) {
                            log("Expected file part index: $filePartIndex, Received: $receivedIndex")
                            break
                        }

                        if (bytesRead <= 0) break
                    }

                    if (!partOk) return false

                    bytesTransferred += fileTransferPacketSize
                    if (bytesTransferred > fileSize) bytesTransferred = fileSize

                    val currentPercent = ((100.0 * bytesTransferred) / fileSize).toInt()
                    if (currentPercent != previousPercent) {
                        previousPercent = currentPercent
                        log("$currentPercent%")
                        onProgress?.invoke(bytesTransferred, fileSize)
                    }
                }

                val sequenceEffectiveByteCount =
                    if (isLastSequence && partialPacketByteCount != 0L) {
                        (fileTransferPacketSize * (lastSequenceSize - 1)) + partialPacketByteCount.toInt()
                    } else {
                        sequenceTotalByteCount
                    }

                val endPacket = if (destination == OdinProtocol.DESTINATION_PHONE) {
                    OdinProtocol.fileTransferEndPhone(
                        sequenceEffectiveByteCount, deviceType, fileIdentifier, isLastSequence
                    )
                } else {
                    OdinProtocol.fileTransferEndModem(
                        sequenceEffectiveByteCount, deviceType, isLastSequence
                    )
                }

                if (!sendPacket(endPacket, DEFAULT_TIMEOUT_SEND, EMPTY_TRANSFER_BEFORE_AND_AFTER)) {
                    log("Failed to end file transfer sequence!")
                    return false
                }

                if (receiveResponse(OdinProtocol.RESPONSE_FILE_TRANSFER, fileTransferSequenceTimeout) == null) {
                    log("Failed to confirm end of file transfer sequence!")
                    return false
                }
            }
        } finally {
            raf.close()
        }

        return true
    }

    // --- Low-level helpers -------------------------------------------------

    private fun sendPacket(
        bytes: ByteArray,
        timeout: Int = DEFAULT_TIMEOUT_SEND,
        emptyTransferFlags: Int = EMPTY_TRANSFER_AFTER
    ): Boolean {
        if (emptyTransferFlags and EMPTY_TRANSFER_BEFORE != 0) {
            transport.sendEmpty(DEFAULT_TIMEOUT_EMPTY_TRANSFER)
        }

        val sent = transport.sendRaw(bytes, timeout)

        if (emptyTransferFlags and EMPTY_TRANSFER_AFTER != 0) {
            transport.sendEmpty(DEFAULT_TIMEOUT_EMPTY_TRANSFER)
        }

        return sent
    }

    /** Reads an 8-byte response, verifying its type. Returns payload, or null on mismatch/failure. */
    private fun receiveResponse(expectedType: Int, timeout: Int = DEFAULT_TIMEOUT_RECEIVE): Int? {
        val buffer = ByteArray(OdinProtocol.RESPONSE_PACKET_SIZE)
        val received = transport.receiveRaw(buffer, timeout)
        if (received != buffer.size) return null
        if (OdinProtocol.readResponseType(buffer) != expectedType) return null
        return OdinProtocol.readResponseValue(buffer)
    }

    companion object {
        private const val DEFAULT_PACKET_SIZE = 131072
        private const val DEFAULT_SEQUENCE_MAX_LENGTH = 800
        private const val UPGRADED_PACKET_SIZE = 1048576
        private const val UPGRADED_SEQUENCE_MAX_LENGTH = 30

        private const val DEFAULT_TIMEOUT_SEND = 3000
        private const val DEFAULT_TIMEOUT_RECEIVE = 3000
        private const val DEFAULT_TIMEOUT_EMPTY_TRANSFER = 100

        private const val EMPTY_TRANSFER_NONE = 0
        private const val EMPTY_TRANSFER_BEFORE = 1
        private const val EMPTY_TRANSFER_AFTER = 1 shl 1
        private const val EMPTY_TRANSFER_BEFORE_AND_AFTER = EMPTY_TRANSFER_BEFORE or EMPTY_TRANSFER_AFTER

        private const val PART_SEND_RETRIES = 4

        private const val PIT_CHUNK_SIZE = OdinProtocol.PIT_FILE_CHUNK_SIZE
    }
}