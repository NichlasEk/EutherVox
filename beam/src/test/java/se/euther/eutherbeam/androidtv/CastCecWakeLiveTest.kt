package se.euther.eutherbeam.androidtv

import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

class CastCecWakeLiveTest {
    @Test
    fun `launches receiver on explicitly selected live puck`() = runBlocking {
        val host = System.getenv("EUTHERBEAM_CAST_LIVE_HOST").orEmpty()
        assumeTrue("Set EUTHERBEAM_CAST_LIVE_HOST for the opt-in live test", host.isNotBlank())
        CastCecWakeClient(trustAllContext()).wake(host)
    }

    private fun trustAllContext(): SSLContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }), SecureRandom())
    }
}
