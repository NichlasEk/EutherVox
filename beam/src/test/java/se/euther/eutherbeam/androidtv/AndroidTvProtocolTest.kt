package se.euther.eutherbeam.androidtv

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidTvProtocolTest {
    @Test
    fun `pairing request matches remote v2 wire format`() {
        assertEquals(
            "080210c80152170a0961747672656d6f7465120a4575746865724265616d",
            AndroidTvProtocol.pairingRequest("EutherBeam").hex(),
        )
    }

    @Test
    fun `pairing negotiation messages match known vectors`() {
        assertEquals("080210c801a201080a04080310061801", AndroidTvProtocol.pairingOptions().hex())
        assertEquals("080210c801f201080a04080310061001", AndroidTvProtocol.pairingConfiguration().hex())
    }

    @Test
    fun `short dpad key uses remote key inject field`() {
        assertEquals("520408131003", AndroidTvProtocol.remoteKey(AndroidTvKey.DPAD_UP.code).hex())
    }

    @Test
    fun `framing supports protobuf varint lengths`() {
        val payload = ByteArray(300) { it.toByte() }
        val framed = ByteArrayOutputStream().also { ProtoWire.frame(it, payload) }.toByteArray()
        assertEquals(0xac.toByte(), framed[0])
        assertEquals(0x02.toByte(), framed[1])
        assertArrayEquals(payload, ProtoWire.readFrame(ByteArrayInputStream(framed)))
    }

    @Test
    fun `pairing response parser validates status and field`() {
        val ack = ProtoWire.message {
            uint(1, 2)
            uint(2, 200)
            nested(31) { }
        }
        assertEquals(31, AndroidTvProtocol.pairingField(ack))
    }

    @Test
    fun `remote ping preserves request value`() {
        val request = ProtoWire.message { nested(8) { uint(1, 9876) } }
        assertEquals(9876L, AndroidTvProtocol.pingValue(request))
        assertTrue(9 in AndroidTvProtocol.topLevelFields(AndroidTvProtocol.remotePingResponse(9876)))
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}
