package com.mystic.freyja.odin

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface

/**
 * Android-native replacement for Heimdall's libusb-based transport.
 * Ports BridgeManager::FindDeviceInterface / ClaimDeviceInterface /
 * SendBulkTransfer / ReceiveBulkTransfer / InitialiseProtocol using
 * UsbDeviceConnection instead of libusb - no NDK dependency needed.
 */
class OdinTransport(private val device: UsbDevice, private val connection: UsbDeviceConnection) {

    private var usbInterface: UsbInterface? = null
    private var inEndpoint: UsbEndpoint? = null
    private var outEndpoint: UsbEndpoint? = null

    /** Optional extra diagnostics, enabled via the Verbose checkbox. */
    var onTransportLog: ((String) -> Unit)? = null

    companion object {
        private const val USB_CLASS_CDC_DATA = 0x0A
        private const val HANDSHAKE_TIMEOUT = 1000
        private const val HANDSHAKE_READ_TIMEOUT = 2000
        private const val HANDSHAKE_ATTEMPTS = 3
        private const val HANDSHAKE_RETRY_DELAY = 250
        private const val DEFAULT_TIMEOUT = 3000
        private const val RETRY_DELAY = 250
        private const val MAX_TRANSFER_RETRIES = 5

        /**
         * libusb (and so Heimdall) splits large bulk transfers into URBs of
         * about USBFS_MAX_BUFFER_SIZE (16 KiB). Android's bulkTransfer issues
         * one shot per call and some hosts fail on multi-hundred-KiB writes,
         * so we chunk the same way - aligned to the endpoint max packet size.
         */
        private const val CHUNK_MULTIPLE = 32
    }

    /** Finds the CDC-data interface (2 bulk endpoints) and claims it. */
    fun claimInterface(): Boolean {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass != USB_CLASS_CDC_DATA || iface.endpointCount != 2) continue

            var candidateIn: UsbEndpoint? = null
            var candidateOut: UsbEndpoint? = null
            for (e in 0 until iface.endpointCount) {
                val endpoint = iface.getEndpoint(e)
                if (endpoint.direction == UsbConstants.USB_DIR_IN) candidateIn = endpoint
                else candidateOut = endpoint
            }

            if (candidateIn != null && candidateOut != null) {
                if (!connection.claimInterface(iface, true)) continue
                usbInterface = iface
                inEndpoint = candidateIn
                outEndpoint = candidateOut
                onTransportLog?.invoke(
                    "interface claimed: IN ${candidateIn.address.toString(16)} " +
                        "OUT ${candidateOut.address.toString(16)} " +
                        "maxPacket ${candidateOut.maxPacketSize}"
                )
                return true
            }
        }
        return false
    }

    fun releaseInterface() {
        usbInterface?.let { connection.releaseInterface(it) }
    }

    fun sendRaw(data: ByteArray, timeout: Int = DEFAULT_TIMEOUT, retry: Boolean = true): Boolean {
        val out = outEndpoint ?: return false
        val chunkSize = (out.maxPacketSize * CHUNK_MULTIPLE).coerceAtLeast(512)
        var offset = 0
        var attempt = 0
        while (offset < data.size) {
            val length = minOf(chunkSize, data.size - offset)
            var sent = connection.bulkTransfer(out, data.copyOfRange(offset, offset + length), length, timeout)
            while (sent != length) {
                if (!retry || attempt >= MAX_TRANSFER_RETRIES) {
                    onTransportLog?.invoke("bulk OUT failed: wrote $sent of $length bytes, offset $offset")
                    return false
                }
                attempt++
                Thread.sleep(RETRY_DELAY.toLong() * attempt)
                sent = connection.bulkTransfer(out, data.copyOfRange(offset, offset + length), length, timeout)
            }
            offset += length
        }
        return true
    }

    fun sendEmpty(timeout: Int = 100): Boolean {
        val out = outEndpoint ?: return false
        return connection.bulkTransfer(out, ByteArray(0), 0, timeout) >= 0
    }

    fun receiveRaw(buffer: ByteArray, timeout: Int = DEFAULT_TIMEOUT, retry: Boolean = true): Int {
        val inEp = inEndpoint ?: return -1
        var received = connection.bulkTransfer(inEp, buffer, buffer.size, timeout)
        if (received < 0 && retry) {
            for (i in 0 until 5) {
                Thread.sleep(RETRY_DELAY.toLong() * (i + 1))
                received = connection.bulkTransfer(inEp, buffer, buffer.size, timeout)
                if (received >= 0) break
            }
        }
        return received
    }

    fun receiveEmpty(timeout: Int = 100) {
        val inEp = inEndpoint ?: return
        connection.bulkTransfer(inEp, ByteArray(1), 1, timeout)
    }

    /** Send "ODIN", expect "LOKE" back - ports BridgeManager::InitialiseProtocol. */
    fun handshake(): Boolean {
        val odin = "ODIN".toByteArray(Charsets.US_ASCII)
        val response = ByteArray(7)

        for (attempt in 1..HANDSHAKE_ATTEMPTS) {
            if (!sendRaw(odin, HANDSHAKE_TIMEOUT)) {
                onTransportLog?.invoke("handshake: attempt $attempt failed to send ODIN")
                continue
            }

            val received = receiveRaw(response, HANDSHAKE_READ_TIMEOUT)
            if (received == 4) {
                val loke = response[0] == 'L'.code.toByte() &&
                    response[1] == 'O'.code.toByte() &&
                    response[2] == 'K'.code.toByte() &&
                    response[3] == 'E'.code.toByte()

                if (loke) return true

                val hex = response.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
                onTransportLog?.invoke("handshake: unexpected response bytes: $hex")
                return false
            }

            onTransportLog?.invoke("handshake: attempt $attempt - expected 4 bytes, received $received")
            if (attempt < HANDSHAKE_ATTEMPTS) Thread.sleep((HANDSHAKE_RETRY_DELAY * attempt).toLong())
        }

        return false
    }
}