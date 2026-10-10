package se.euther.eutherbeam.androidtv

import org.junit.Assert.assertEquals
import org.junit.Test

class CastV2Test {
    @Test
    fun `encodes receiver launch message`() {
        val payload = CastV2.message("urn:x-cast:com.google.cast.receiver", "{\"type\":\"LAUNCH\"}")
        val fields = ProtoWire.fields(payload)
        assertEquals(0L, fields.first { it.number == 1 }.varint)
        assertEquals("sender-0", fields.first { it.number == 2 }.bytes!!.toString(Charsets.UTF_8))
        assertEquals("receiver-0", fields.first { it.number == 3 }.bytes!!.toString(Charsets.UTF_8))
        assertEquals("urn:x-cast:com.google.cast.receiver", fields.first { it.number == 4 }.bytes!!.toString(Charsets.UTF_8))
        assertEquals("{\"type\":\"LAUNCH\"}", fields.first { it.number == 6 }.bytes!!.toString(Charsets.UTF_8))
    }
}
