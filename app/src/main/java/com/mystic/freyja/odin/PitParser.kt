package com.mystic.freyja.odin

/**
 * Parses the binary PIT (Partition Information Table) format, ported from
 * libpit.h / libpit.cpp. Only read-support is implemented - we only need
 * to look up an existing partition's deviceType + identifier by name in
 * order to flash it; we don't build/flash new PIT tables in this pass.
 */
data class PitEntry(
    val binaryType: Int,
    val deviceType: Int,
    val identifier: Int,
    val partitionName: String
)

object PitParser {

    private const val FILE_IDENTIFIER = 0x12349876.toInt()
    private const val HEADER_SIZE = 28
    private const val ENTRY_SIZE = 132
    private const val PARTITION_NAME_OFFSET = 36
    private const val PARTITION_NAME_MAX_LENGTH = 32
    private const val PADDED_SIZE_MULTIPLICAND = 4096

    /** Returns null if the buffer isn't a valid PIT file. */
    fun parse(data: ByteArray): List<PitEntry>? {
        if (data.size < HEADER_SIZE) return null

        val magic = OdinProtocol.getIntLE(data, 0)
        if (magic != FILE_IDENTIFIER) return null

        val entryCount = OdinProtocol.getIntLE(data, 4)
        val entries = mutableListOf<PitEntry>()

        for (i in 0 until entryCount) {
            val base = HEADER_SIZE + i * ENTRY_SIZE
            if (base + ENTRY_SIZE > data.size) break

            val binaryType = OdinProtocol.getIntLE(data, base)
            val deviceType = OdinProtocol.getIntLE(data, base + 4)
            val identifier = OdinProtocol.getIntLE(data, base + 8)

            val nameBytes = data.copyOfRange(
                base + PARTITION_NAME_OFFSET,
                base + PARTITION_NAME_OFFSET + PARTITION_NAME_MAX_LENGTH
            )
            val nullIndex = nameBytes.indexOf(0).let { if (it < 0) nameBytes.size else it }
            val partitionName = String(nameBytes, 0, nullIndex, Charsets.US_ASCII)

            entries.add(PitEntry(binaryType, deviceType, identifier, partitionName))
        }

        return entries
    }

    /** Case-insensitive lookup, since users may type slot names in any case. */
    fun findByName(entries: List<PitEntry>, name: String): PitEntry? {
        return entries.firstOrNull { it.partitionName.equals(name, ignoreCase = true) }
    }

    /**
     * Heimdall's PitData::GetPaddedSize - the size actually flashed for a PIT
     * file. PIT data is padded up to the next multiple of 4096 bytes so it can
     * be sent as a single file part. Mirrors libpit.cpp:363-373.
     */
    fun computePaddedSize(entryCount: Int): Int {
        require(entryCount >= 0) { "Negative PIT entry count" }
        val dataSize = HEADER_SIZE + entryCount * ENTRY_SIZE
        val paddedSize = (dataSize / PADDED_SIZE_MULTIPLICAND) * PADDED_SIZE_MULTIPLICAND
        return if (dataSize % PADDED_SIZE_MULTIPLICAND != 0) paddedSize + PADDED_SIZE_MULTIPLICAND else paddedSize
    }

    /** Pads a raw PIT file to its padded size so it can be flashed as-is. */
    fun padToPaddedSize(data: ByteArray): ByteArray {
        val entryCount = OdinProtocol.getIntLE(data, 4)
        val paddedSize = computePaddedSize(entryCount)
        return if (data.size >= paddedSize) data.copyOf(data.size) else data.copyOf(paddedSize)
    }
}