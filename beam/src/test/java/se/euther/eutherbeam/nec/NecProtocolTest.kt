package se.euther.eutherbeam.nec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NecProtocolTest {
    @Test
    fun `power packets match physically tested NecFjarr implementation`() {
        assertEquals("01304130413036023031443603740D", NecProtocol.POWER_STATUS_QUERY.toHex())
        assertEquals("01304130413043024332303344363030303103730D", NecProtocol.POWER_ON.toHex())
        assertEquals("01304130413043024332303344363030303403760D", NecProtocol.POWER_OFF.toHex())
    }

    @Test
    fun `set packet retains input code and valid check byte`() {
        assertEquals("0130413045304102303036303030313103720D", NecProtocol.buildSetPacket("0011").toHex())
    }

    @Test
    fun `valid power reply is accepted and corrupted reply rejected`() {
        val reply = buildReply("0200D60000040001")
        assertTrue(NecProtocol.isValidPowerStatusResponse(reply))
        reply[reply.lastIndex - 1] = (reply[reply.lastIndex - 1].toInt() xor 1).toByte()
        assertFalse(NecProtocol.isValidPowerStatusResponse(reply))
    }

    private fun buildReply(message: String): ByteArray {
        val raw = byteArrayOf(1) + "00AB12".toByteArray() + byteArrayOf(2) + message.toByteArray() + byteArrayOf(3)
        var bcc = raw[1].toInt()
        for (index in 2 until raw.size) bcc = bcc xor raw[index].toInt()
        return raw + byteArrayOf(bcc.toByte(), 13)
    }

    private fun ByteArray.toHex() = joinToString("") { "%02X".format(it) }
}
