package com.mystic.freyja.odin

/**
 * Pure byte-level framing for the Odin/Heimdall protocol. No Android or USB
 * code here on purpose - this is a faithful, testable port of Heimdall's
 * ControlPacket / ResponsePacket family (BridgeManager.cpp / *.h).
 * All multi-byte integers are little-endian, matching Heimdall's Pack/Unpack.
 */
object OdinProtocol {

    // ControlPacket::k* (outbound packet "type" at offset 0)
    const val CONTROL_SESSION = 0x64
    const val CONTROL_PIT = 0x65
    const val CONTROL_FILE_TRANSFER = 0x66
    const val CONTROL_END_SESSION = 0x67

    // SessionSetupPacket::k*
    const val SESSION_BEGIN = 0
    const val SESSION_TOTAL_BYTES = 2
    const val SESSION_FILE_PART_SIZE = 5

    // PitFilePacket::k*
    const val PIT_REQUEST_FLASH = 0
    const val PIT_REQUEST_DUMP = 1
    const val PIT_REQUEST_PART = 2
    const val PIT_REQUEST_END_TRANSFER = 3

    // FileTransferPacket::k*
    const val FILE_REQUEST_FLASH = 0
    const val FILE_REQUEST_PART = 2
    const val FILE_REQUEST_END = 3

    // EndSessionPacket::k*
    const val END_SESSION = 0
    const val REBOOT_DEVICE = 1

    // EndFileTransferPacket::k*
    const val DESTINATION_PHONE = 0
    const val DESTINATION_MODEM = 1

    // ResponsePacket::k* (inbound "type" at offset 0)
    const val RESPONSE_SEND_FILE_PART = 0x00
    const val RESPONSE_SESSION_SETUP = 0x64
    const val RESPONSE_PIT_FILE = 0x65
    const val RESPONSE_FILE_TRANSFER = 0x66
    const val RESPONSE_END_SESSION = 0x67

    const val OUTBOUND_PACKET_SIZE = 1024
    const val RESPONSE_PACKET_SIZE = 8
    const val PIT_FILE_CHUNK_SIZE = 500 // ReceiveFilePartPacket::kDataSize

    const val DEFAULT_PACKET_SIZE = 131072
    const val DEFAULT_SEQUENCE_MAX_LENGTH = 800
    const val UPGRADED_PACKET_SIZE = 1048576
    const val UPGRADED_SEQUENCE_MAX_LENGTH = 30

    fun putIntLE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
        buf[offset + 2] = ((value shr 16) and 0xFF).toByte()
        buf[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    fun getIntLE(buf: ByteArray, offset: Int): Int {
        return (buf[offset].toInt() and 0xFF) or
            ((buf[offset + 1].toInt() and 0xFF) shl 8) or
            ((buf[offset + 2].toInt() and 0xFF) shl 16) or
            ((buf[offset + 3].toInt() and 0xFF) shl 24)
    }
	
	fun sessionPacket(request: Int, extraInt: Int? = null): ByteArray {
        val buf = ByteArray(OUTBOUND_PACKET_SIZE)
        putIntLE(buf, 0, CONTROL_SESSION)
        putIntLE(buf, 4, request)
        if (extraInt != null) putIntLE(buf, 8, extraInt)
        return buf
    }

    fun pitPacket(request: Int, extraInt: Int? = null): ByteArray {
        val buf = ByteArray(OUTBOUND_PACKET_SIZE)
        putIntLE(buf, 0, CONTROL_PIT)
        putIntLE(buf, 4, request)
        if (extraInt != null) putIntLE(buf, 8, extraInt)
        return buf
    }

    fun fileTransferFlashStart(): ByteArray {
        val buf = ByteArray(OUTBOUND_PACKET_SIZE)
        putIntLE(buf, 0, CONTROL_FILE_TRANSFER)
        putIntLE(buf, 4, FILE_REQUEST_FLASH)
        return buf
    }

    fun fileTransferPartStart(sequenceByteCount: Int): ByteArray {
        val buf = ByteArray(OUTBOUND_PACKET_SIZE)
        putIntLE(buf, 0, CONTROL_FILE_TRANSFER)
        putIntLE(buf, 4, FILE_REQUEST_PART)
        putIntLE(buf, 8, sequenceByteCount)
        return buf
    }

    fun fileTransferEndPhone(sequenceByteCount: Int, deviceType: Int, fileIdentifier: Int, endOfFile: Boolean): ByteArray {
        val buf = ByteArray(OUTBOUND_PACKET_SIZE)
        putIntLE(buf, 0, CONTROL_FILE_TRANSFER)
        putIntLE(buf, 4, FILE_REQUEST_END)
        putIntLE(buf, 8, DESTINATION_PHONE)
        putIntLE(buf, 12, sequenceByteCount)
        putIntLE(buf, 16, 0)
        putIntLE(buf, 20, deviceType)
        putIntLE(buf, 24, fileIdentifier)
        putIntLE(buf, 28, if (endOfFile) 1 else 0)
        return buf
    }

    /** EndModemFileTransferPacket: endOfFile sits at offset 24 (no file identifier). */
    fun fileTransferEndModem(sequenceByteCount: Int, deviceType: Int, endOfFile: Boolean): ByteArray {
        val buf = ByteArray(OUTBOUND_PACKET_SIZE)
        putIntLE(buf, 0, CONTROL_FILE_TRANSFER)
        putIntLE(buf, 4, FILE_REQUEST_END)
        putIntLE(buf, 8, DESTINATION_MODEM)
        putIntLE(buf, 12, sequenceByteCount)
        putIntLE(buf, 16, 0)
        putIntLE(buf, 20, deviceType)
        putIntLE(buf, 24, if (endOfFile) 1 else 0)
        return buf
    }
    fun endSessionPacket(request: Int): ByteArray {
        val buf = ByteArray(OUTBOUND_PACKET_SIZE)
        putIntLE(buf, 0, CONTROL_END_SESSION)
        putIntLE(buf, 4, request)
        return buf
    }

    fun dumpPartPitPacket(partIndex: Int): ByteArray = pitPacket(PIT_REQUEST_PART, partIndex)

    /** All plain 8-byte responses: type at 0, one payload int at 4. */
    fun readResponseType(buf: ByteArray): Int = getIntLE(buf, 0)
    fun readResponseValue(buf: ByteArray): Int = getIntLE(buf, 4)
}