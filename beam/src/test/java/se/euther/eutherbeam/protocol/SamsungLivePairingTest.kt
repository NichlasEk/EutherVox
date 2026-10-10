package se.euther.eutherbeam.protocol

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeNotNull
import org.junit.Test

/** Opt-in hardware test. It is skipped unless the corresponding environment variables are set. */
class SamsungLivePairingTest {
    @Test
    fun requestPinFromRealTv() {
        val host = System.getenv("EUTHERBEAM_TV_IP")
        assumeNotNull(host)
        runBlocking { client(checkNotNull(host)).requestPin() }
    }

    @Test
    fun pairWithRealTv() {
        val host = System.getenv("EUTHERBEAM_TV_IP")
        val pin = System.getenv("EUTHERBEAM_TV_PIN")
        assumeNotNull(host, pin)
        runBlocking {
            val identity = client(checkNotNull(host)).confirmPin(checkNotNull(pin))
            check(identity.aesKey.size == 16)
            check(identity.sessionId.isNotBlank())
        }
    }

    @Test
    fun pairAndSendKeyToRealTv() {
        val host = System.getenv("EUTHERBEAM_TV_IP")
        val pin = System.getenv("EUTHERBEAM_TV_PIN")
        val key = System.getenv("EUTHERBEAM_TV_KEY")
        val duid = System.getenv("EUTHERBEAM_TV_DUID")
        assumeNotNull(host, pin, key, duid)
        runBlocking {
            val pairingClient = client(checkNotNull(host))
            val identity = pairingClient.confirmPin(checkNotNull(pin))
            SamsungRemoteSession(checkNotNull(host), identity, checkNotNull(duid)).sendKey(checkNotNull(key))
        }
    }

    private fun client(host: String) = SamsungPairingClient(
        host = host,
        appId = "12345",
        deviceId = "71e8bc55-c6cd-4fca-b753-2d4bfe200001",
        userId = "654321",
    )
}
