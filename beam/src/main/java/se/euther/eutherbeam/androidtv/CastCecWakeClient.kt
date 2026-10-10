package se.euther.eutherbeam.androidtv

import java.io.DataOutputStream
import java.net.InetSocketAddress
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal class CastCecWakeClient(private val sslContext: SSLContext) {
    suspend fun wake(host: String, port: Int = 8009) = withContext(Dispatchers.IO) {
        val socket = sslContext.socketFactory.createSocket() as SSLSocket
        socket.use {
            it.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            it.soTimeout = READ_TIMEOUT_MS
            it.startHandshake()
            val output = DataOutputStream(it.outputStream)
            write(output, CastV2.message(CONNECTION_NAMESPACE, "{\"type\":\"CONNECT\",\"origin\":{}}"))
            write(output, CastV2.message(RECEIVER_NAMESPACE, "{\"type\":\"LAUNCH\",\"appId\":\"$YOUTUBE_APP_ID\",\"requestId\":1}"))
            delay(250)
        }
    }

    private fun write(output: DataOutputStream, payload: ByteArray) {
        output.writeInt(payload.size)
        output.write(payload)
        output.flush()
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 3_000
        const val READ_TIMEOUT_MS = 3_000
        const val CONNECTION_NAMESPACE = "urn:x-cast:com.google.cast.tp.connection"
        const val RECEIVER_NAMESPACE = "urn:x-cast:com.google.cast.receiver"
        const val YOUTUBE_APP_ID = "233637DE"
    }
}

internal object CastV2 {
    fun message(namespace: String, json: String): ByteArray = ProtoWire.message {
        uint(1, 0) // CASTV2_1_0
        string(2, "sender-0")
        string(3, "receiver-0")
        string(4, namespace)
        uint(5, 0) // STRING
        string(6, json)
    }
}
