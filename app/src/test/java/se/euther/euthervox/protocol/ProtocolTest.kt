package se.euther.euthervox.protocol

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    @Test fun sessionStartAdvertisesExactPcmFormat() {
        val json = JsonParser.parseString(sessionStart("pixel")).asJsonObject
        val audio = json["input_audio"].asJsonObject
        assertEquals(1, json["protocol_version"].asInt)
        assertEquals("pixel", json["node_name"].asString)
        assertEquals("pcm_s16le", audio["codec"].asString)
        assertEquals(16_000, audio["sample_rate"].asInt)
        assertEquals(20, audio["frame_ms"].asInt)
    }

    @Test fun parsesTtsStart() {
        val event = parseServerEvent("""{"type":"tts.start","utterance_id":"u1","audio":{"codec":"pcm_s16le","sample_rate":24000,"channels":1}}""")
        assertTrue(event is ServerEvent.TtsStart)
        assertEquals(24_000, (event as ServerEvent.TtsStart).sampleRate)
    }

    @Test fun parsesRecoverableError() {
        val event = parseServerEvent("""{"type":"error","code":"STT_FAILED","message":"no speech","recoverable":true}""")
        assertEquals(ServerEvent.Error("STT_FAILED", "no speech", true), event)
    }
}
