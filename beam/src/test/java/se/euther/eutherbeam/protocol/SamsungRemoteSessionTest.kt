package se.euther.eutherbeam.protocol

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test

class SamsungRemoteSessionTest {
    @Test
    fun `remote key command uses TV duid and string false parameter`() {
        val key = ByteArray(16) { it.toByte() }
        val session = SamsungRemoteSession("192.0.2.1", SamsungIdentity("42", key), "uuid:real-tv-duid")
        val message = session.commandMessage("KEY_VOLUP", "uuid:real-tv-duid")
        val payload = JsonParser.parseString(message.removePrefix("5::/com.samsung.companion:")).asJsonObject
        val encryptedBody = payload.getAsJsonArray("args")[0].asJsonObject.get("body").asString
        val encrypted = JsonParser.parseString(encryptedBody).asJsonArray
            .map { it.asInt.toByte() }
            .toByteArray()
        val command = JsonParser.parseString(SamsungHSeriesCrypto().decryptCommand(key, encrypted)).asJsonObject
        val body = command.getAsJsonObject("body")

        assertEquals("callCommon", payload.get("name").asString)
        assertEquals(42, payload.getAsJsonArray("args")[0].asJsonObject.get("Session_Id").asInt)
        assertEquals("uuid:real-tv-duid", body.get("param1").asString)
        assertEquals("false", body.get("param4").asString)
        assertEquals("KEY_VOLUP", body.get("param3").asString)
    }
}
