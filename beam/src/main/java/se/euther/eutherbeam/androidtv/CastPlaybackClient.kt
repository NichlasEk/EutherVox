package se.euther.eutherbeam.androidtv

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class PlaybackSnapshot(val app: String? = null, val title: String? = null, val state: String? = null) {
    fun describe(): String {
        val label = when (state) { "PLAYING" -> "Spelar"; "PAUSED" -> "Pausad"; "BUFFERING" -> "Buffrar"; else -> "Laddat innehåll" }
        return if (title != null) "Cast rapporterar: $label – $title" + (app?.let { " ($it)." } ?: ".")
        else app?.let { "Cast-appen är $it. Filmtiteln är inte tillgänglig." }
            ?: "Pucken lämnar ingen information om filmtiteln just nu."
    }
}

/** Read-only receiver/media queries. Never launches a Cast app or loads media. */
internal class CastPlaybackClient(private val sslContext: SSLContext) {
    suspend fun read(host: String, port: Int = 8009): PlaybackSnapshot = withContext(Dispatchers.IO) {
        (sslContext.socketFactory.createSocket() as SSLSocket).use { socket ->
            socket.connect(InetSocketAddress(host, port), 2_000)
            socket.soTimeout = 2_000
            socket.startHandshake()
            val input = DataInputStream(socket.inputStream)
            val output = DataOutputStream(socket.outputStream)
            fun send(namespace: String, body: String, destination: String = "receiver-0") {
                val frame = CastV2.message(namespace, body, destination)
                output.writeInt(frame.size); output.write(frame); output.flush()
            }
            send(CONNECTION, "{\"type\":\"CONNECT\",\"origin\":{}}")
            send(RECEIVER, "{\"type\":\"GET_STATUS\",\"requestId\":1}")
            val deadline = System.nanoTime() + 5_000_000_000L
            var app: String? = null
            var transport: String? = null
            repeat(20) {
                if (System.nanoTime() >= deadline) return@withContext PlaybackSnapshot(app)
                socket.soTimeout = ((deadline - System.nanoTime()) / 1_000_000).toInt().coerceIn(1, 2000)
                val size = input.readInt()
                require(size in 1..65536) { "Ogiltigt Cast-svar" }
                val bytes = ByteArray(size); input.readFully(bytes)
                val fields = ProtoWire.fields(bytes)
                fun text(field: Int) = fields.firstOrNull { it.number == field }?.bytes?.toString(Charsets.UTF_8)
                val namespace = text(4)
                val message = JsonParser.parseString(text(6) ?: return@repeat).asJsonObject
                if (namespace == HEARTBEAT && message["type"]?.asString == "PING") {
                    send(HEARTBEAT, "{\"type\":\"PONG\"}", text(2) ?: "receiver-0")
                } else if (namespace == RECEIVER && message["type"]?.asString == "RECEIVER_STATUS" && transport == null) {
                    val applications = message["status"]?.asJsonObject?.getAsJsonArray("applications")
                    val active = applications?.firstOrNull()?.asJsonObject ?: return@withContext PlaybackSnapshot()
                    app = active["displayName"]?.asString?.take(120)
                    val mediaSupported = active.getAsJsonArray("namespaces")?.any { it.asJsonObject["name"]?.asString == MEDIA } == true
                    transport = active["transportId"]?.asString
                    if (!mediaSupported || transport.isNullOrBlank()) return@withContext PlaybackSnapshot(app)
                    send(CONNECTION, "{\"type\":\"CONNECT\",\"origin\":{}}", transport!!)
                    send(MEDIA, "{\"type\":\"GET_STATUS\",\"requestId\":2}", transport!!)
                } else if (namespace == MEDIA && text(2) == transport && message["type"]?.asString == "MEDIA_STATUS") {
                    return@withContext parseMedia(message, app)
                }
            }
            PlaybackSnapshot(app)
        }
    }
    companion object {
        const val CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
        const val RECEIVER = "urn:x-cast:com.google.cast.receiver"
        const val MEDIA = "urn:x-cast:com.google.cast.media"
        const val HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
        fun parseMedia(message: JsonObject, app: String?): PlaybackSnapshot {
            val status = message.getAsJsonArray("status")?.firstOrNull()?.asJsonObject ?: return PlaybackSnapshot(app)
            val metadata = status["media"]?.asJsonObject?.get("metadata")?.asJsonObject
            val title = (metadata?.get("title") ?: metadata?.get("seriesTitle"))?.asString?.take(240)?.takeIf { it.isNotBlank() }
            return PlaybackSnapshot(app, title, status["playerState"]?.asString)
        }
    }
}
