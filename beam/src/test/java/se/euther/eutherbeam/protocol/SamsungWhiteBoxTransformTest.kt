package se.euther.eutherbeam.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class SamsungWhiteBoxTransformTest {
    @Test
    fun matchesReferenceImplementation() {
        val vectors = mapOf(
            "0000000000000000000000000000000000000000" to "fe654e41c4d13f42103ca0f4f975d82a",
            "00112233445566778899aabbccddeeff00112233" to "5b62b050e1a7664856374f659262c2e5",
            "da39a3ee5e6b4b0d3255bfef95601890afd80709" to "2685c06576ec57f15189be6945bf95ce",
        )

        vectors.forEach { (input, expected) ->
            assertArrayEquals(expected.hexToBytes(), SamsungWhiteBoxTransform.transform(input.hexToBytes()))
        }
    }
}
