package se.euther.eutherbeam.protocol

/** Samsung's embedded white-box transform used to derive the H-series command key. */
internal object SamsungWhiteBoxTransform {
    private const val ROUNDS = 3
    private const val TABLE_RESOURCE = "/samsung_h_key_transform.bin"

    private val table: ByteArray by lazy {
        checkNotNull(SamsungWhiteBoxTransform::class.java.getResourceAsStream(TABLE_RESOURCE)) {
            "Missing Samsung H-series transform table"
        }.use { it.readBytes() }.also {
            check(it.size == 49_353) { "Invalid Samsung H-series transform table" }
        }
    }

    fun transform(input: ByteArray): ByteArray {
        require(input.size >= 16) { "Samsung transform requires at least 16 bytes" }

        val pbox = ROUNDS * 16_384
        val wpbox = pbox + (ROUNDS + 1) * 32
        val state = IntArray(16)
        val previous = IntArray(16)
        for (index in 0 until 16) {
            val target = value(pbox + index) and 0x0f
            val byte = input[index].toInt() and 0xff
            state[target] = byte
            previous[target] = byte
        }

        var round = 0
        val lastNormalRound = ROUNDS - 1
        var pboxRoundPointer = 6
        do {
            val roundBase = round shl 4
            var state3 = 3
            var state2 = 2
            var state1 = 1
            var state0 = 0
            var tableBase3 = (roundBase + 3) * 16
            var tableBase2 = (roundBase + 2) * 16
            var tableOffset = 1_024
            val lookup = IntArray(64)
            var lookup0 = 0
            val tableBase0 = roundBase * 16
            var rollingBase0 = tableBase0
            var lookup3 = 12
            var lookup2 = 8
            var lookup1 = 4
            do {
                val byte0 = state[state0]
                lookup[lookup0] = (((byte0 ushr 4) + rollingBase0) * 16 + (byte0 and 0x0f)) * 4
                val byte1 = state[state1]
                lookup[lookup1] = (((byte1 ushr 4) + tableBase0) * 16 + (byte1 and 0x0f)) * 4 + tableOffset
                val byte2 = state[state2]
                lookup[lookup2] = (((byte2 ushr 4) + tableBase2) * 16 + (byte2 and 0x0f)) * 4
                val byte3 = state[state3]
                lookup[lookup3] = (((byte3 ushr 4) + tableBase3) * 16 + (byte3 and 0x0f)) * 4

                rollingBase0 += 64
                tableBase2 += 64
                tableBase3 += 64
                state0 += 4
                state1 += 4
                state2 += 4
                state3 += 4
                tableOffset += 4_096
                lookup0 += 16
                lookup1 += 16
                lookup2 += 16
                lookup3 += 16
            } while (tableOffset < 17_408)

            round++
            pboxRoundPointer += 32
            var permutationPointer = pboxRoundPointer
            repeat(4) {
                val nibble3 = value(pbox + permutationPointer + 1) and 0x0f
                val nibble2 = value(pbox + permutationPointer) and 0x0f
                val nibble1 = value(pbox + permutationPointer - 1) and 0x0f
                val nibble0 = value(pbox + permutationPointer - 2) and 0x0f
                var lookup3Pointer = value(wpbox + (previous[nibble3] and 0x0f) % 4 + round * 4 - 4) * 4
                var lookup2Pointer = value(wpbox + (previous[nibble2] and 0x0f) % 4 + round * 4 - 4) * 4
                var lookup1Pointer = value(wpbox + (previous[nibble1] and 0x0f) % 4 + round * 4 - 4) * 4
                var lookup0Pointer = value(wpbox + (previous[nibble0] and 0x0f) % 4 + round * 4 - 4) * 4
                var outputPointer = permutationPointer - 6
                repeat(4) {
                    val outputIndex = value(pbox + outputPointer) and 0x0f
                    state[outputIndex] =
                        value(lookup[nibble0 * 4] + BYTE_PERMUTATIONS[lookup0Pointer]) xor
                        value(lookup[nibble2 * 4] + BYTE_PERMUTATIONS[lookup2Pointer]) xor
                        value(lookup[nibble1 * 4] + BYTE_PERMUTATIONS[lookup1Pointer]) xor
                        value(lookup[nibble3 * 4] + BYTE_PERMUTATIONS[lookup3Pointer])
                    lookup0Pointer++
                    lookup1Pointer++
                    lookup2Pointer++
                    lookup3Pointer++
                    outputPointer++
                }
                permutationPointer += 8
            }
            state.copyInto(previous)
        } while (round < lastNormalRound)

        val output = ByteArray(16)
        for (index in 0 until 16) {
            val outputIndex = value(pbox + ((index ushr 3) + ROUNDS * 4) * 8 + (index and 7)) and 0x0f
            val byte = previous[index]
            val permutation = BYTE_PERMUTATIONS[4 * value(wpbox + (byte and 3) + 8)]
            output[outputIndex] = value(0x8000 + index * 1_024 +
                4 * (16 * (byte ushr 4) + (byte and 0x0f)) + permutation).toByte()
        }
        return output
    }

    private fun value(index: Int): Int = table[index].toInt() and 0xff

    private val BYTE_PERMUTATIONS = intArrayOf(
        0, 1, 2, 3, 0, 1, 3, 2, 0, 2, 1, 3, 0, 2, 3, 1,
        0, 3, 1, 2, 0, 3, 2, 1, 1, 0, 2, 3, 1, 0, 3, 2,
        1, 2, 0, 3, 1, 2, 3, 0, 1, 3, 0, 2, 1, 3, 2, 0,
        2, 0, 1, 3, 2, 0, 3, 1, 2, 1, 0, 3, 2, 1, 3, 0,
        2, 3, 0, 1, 2, 3, 1, 0, 3, 0, 1, 2, 3, 0, 2, 1,
        3, 1, 0, 2, 3, 1, 2, 0, 3, 2, 0, 1, 3, 2, 1, 0,
    )
}
