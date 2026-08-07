package se.euther.euthervox.protocol

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    @Test fun sessionStartAdvertisesExactPcmFormat() {
        val json = JsonParser.parseString(sessionStart("pixel", character = "christian-grosshandlare", voiceId = "moss-christian")).asJsonObject
        val audio = json["input_audio"].asJsonObject
        assertEquals(1, json["protocol_version"].asInt)
        assertEquals("pixel", json["node_name"].asString)
        assertEquals("christian-grosshandlare", json["character"].asString)
        assertEquals("moss-christian", json["voice_id"].asString)
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

    @Test fun parsesAllowlistedDeviceAction() {
        val event = parseServerEvent(
            """{"type":"action.request","action_id":"a1","utterance_id":"u1","name":"media.play","target":{"kind":"node","node_name":"pixel"},"arguments":{"provider":"youtube_music","query":"något mörkt och lugnt"},"requires_confirmation":false}"""
        )

        assertEquals(
            ServerEvent.ActionRequest("a1", "u1", "media.play", "pixel", "youtube_music", "något mörkt och lugnt", "", false),
            event,
        )
    }

    @Test fun serializesActionResult() {
        val json = JsonParser.parseString(actionResult("a1", "u1", "completed", "klart")).asJsonObject

        assertEquals("action.result", json["type"].asString)
        assertEquals("a1", json["action_id"].asString)
        assertEquals("completed", json["status"].asString)
    }

    @Test fun parsesCastRoomFromPlaylistAction() {
        val event = parseServerEvent(
            """{"type":"action.request","action_id":"a2","utterance_id":"u2","name":"playlist.create","target":{"kind":"node","node_name":"pixel"},"arguments":{"provider":"euthervox","query":"mörk synth","output_room":"köket"},"requires_confirmation":true}"""
        )

        assertEquals("köket", (event as ServerEvent.ActionRequest).outputRoom)
    }

    @Test fun serializesActionConfirmation() {
        val json = JsonParser.parseString(actionConfirm("a1")).asJsonObject
        assertEquals("action.confirm", json["type"].asString)
        assertEquals("a1", json["action_id"].asString)
    }
}
