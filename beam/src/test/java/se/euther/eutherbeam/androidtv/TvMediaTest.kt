package se.euther.eutherbeam.androidtv

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class TvMediaTest {
    @Test fun voiceFramesFollowRemoteV2Schema() {
        assertEquals("f20102082a", AndroidTvProtocol.voiceBegin(42).joinToString("") { "%02x".format(it) })
        assertEquals("820202082a", AndroidTvProtocol.voiceEnd(42).joinToString("") { "%02x".format(it) })
        val payload = AndroidTvProtocol.voicePayload(42, ByteArray(8192) { 7 })
        val body = ProtoWire.nested(payload, 31)!!
        assertEquals(42L, ProtoWire.varint(body, 1))
        assertEquals(8192, ProtoWire.fields(body).first { it.number == 2 }.bytes!!.size)
        assertEquals("com.netflix.ninja", AndroidTvProtocol.currentApp(ProtoWire.message {
            nested(20) { nested(1) { string(12, "com.netflix.ninja") } }
        }))
    }
    @Test fun castMetadataDoesNotTurnUnknownStateIntoPlaying() {
        val snapshot = CastPlaybackClient.parseMedia(JsonParser.parseString("""{"status":[{"playerState":"PAUSED","media":{"metadata":{"title":"Terminator"}}}]}""").asJsonObject, "Netflix")
        assertEquals("Terminator", snapshot.title)
        assertTrue(snapshot.describe().contains("Pausad"))
        val unknown = PlaybackSnapshot("Netflix")
        assertFalse(unknown.describe().contains("Spelar"))
        assertTrue(unknown.describe().contains("inte tillgänglig"))
    }
    @Test fun bindingCannotStealPlayerFromOtherCard() {
        val upstairs = AndroidTvDevice("u", "Upstairs", "192.0.2.1", linkedDisplay = "samsung")
        val spare = AndroidTvDevice("s", "Spare", "192.0.2.2")
        val devices = listOf(upstairs, spare)
        assertThrows(IllegalArgumentException::class.java) { bindPlayer(devices, "nec", "u") }
        val assigned = bindPlayer(devices, "nec", "s")
        assertEquals("samsung", assigned[0].linkedDisplay)
        assertEquals("nec", assigned[1].linkedDisplay)
        val cleared = bindPlayer(assigned, "nec", null)
        assertEquals("samsung", cleared[0].linkedDisplay)
        assertEquals("", cleared[1].linkedDisplay)
    }
}
