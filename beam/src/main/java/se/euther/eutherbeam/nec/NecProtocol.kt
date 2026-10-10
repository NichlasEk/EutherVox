package se.euther.eutherbeam.nec

import java.io.ByteArrayOutputStream

internal object NecProtocol {
    const val PORT = 7142
    val POWER_STATUS_QUERY = "01304130413036023031443603740D".hexToBytes()
    val POWER_ON = "01304130413043024332303344363030303103730D".hexToBytes()
    val POWER_OFF = "01304130413043024332303344363030303403760D".hexToBytes()

    fun buildSetPacket(payload: String): ByteArray {
        val normalized = payload.trim().uppercase()
        require(normalized.length == 4 && normalized.all { it.isDigit() || it in 'A'..'F' }) {
            "NEC-koden måste vara fyra hextecken"
        }
        val raw = ByteArrayOutputStream().apply {
            write(0x01)
            write("0A0E0A".toByteArray(Charsets.US_ASCII))
            write(0x02)
            write("0060".toByteArray(Charsets.US_ASCII))
            write(normalized.toByteArray(Charsets.US_ASCII))
            write(0x03)
        }.toByteArray()
        var bcc = raw[1].toInt() and 0xff
        for (index in 2 until raw.size) bcc = bcc xor (raw[index].toInt() and 0xff)
        return raw + byteArrayOf(bcc.toByte(), 0x0d)
    }

    fun isValidPowerStatusResponse(response: ByteArray): Boolean {
        val delimiter = response.indexOf(0x0d)
        if (delimiter < 9 || response[0] != 0x01.toByte() || response[4] != 'B'.code.toByte()) return false
        val stx = response.indexOf(0x02, endExclusive = delimiter)
        val etx = response.indexOf(0x03, endExclusive = delimiter)
        if (stx < 0 || etx <= stx || delimiter < etx + 2) return false
        var expectedBcc = response[1].toInt() and 0xff
        for (index in 2..etx) expectedBcc = expectedBcc xor (response[index].toInt() and 0xff)
        if ((response[etx + 1].toInt() and 0xff) != expectedBcc) return false
        val message = response.copyOfRange(stx + 1, etx).toString(Charsets.US_ASCII)
        return message.length >= 6 && "D6" in message
    }

    private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun ByteArray.indexOf(value: Int, endExclusive: Int = size): Int {
        for (index in 0 until minOf(endExclusive, size)) if (this[index] == value.toByte()) return index
        return -1
    }
}
