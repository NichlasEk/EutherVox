package se.euther.eutherbeam.protocol

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class SamsungRemoteSession(
    private val host: String,
    private val identity: SamsungIdentity,
    private val duid: String,
    private val crypto: SamsungHSeriesCrypto = SamsungHSeriesCrypto(),
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build(),
) {
    suspend fun sendKey(key: String) = withContext(Dispatchers.IO) {
        startCompanionService()
        val socketId = client.newCall(Request.Builder().url("http://$host:8000/socket.io/1").build())
            .execute().use { response ->
                check(response.isSuccessful) { "TV:n svarade HTTP ${response.code}" }
                response.body?.string()?.substringBefore(':')?.takeIf(String::isNotBlank)
                    ?: error("TV:n gav inget socket-id")
            }
        val socket = withTimeout(4_000) { sendOverSocket(socketId, key) }
        delay(350)
        socket.close(1000, "done")
    }

    private fun startCompanionService() {
        client.newCall(
            Request.Builder()
                .url("http://$host:8000/common/1.0.0/service/startService?appID=com.samsung.companion")
                .build(),
        ).execute().close() // Some H-series firmware returns 404 here but still exposes Socket.IO.
    }

    private suspend fun sendOverSocket(socketId: String, key: String): WebSocket =
        suspendCancellableCoroutine { continuation ->
        lateinit var socket: WebSocket
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = Unit

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.startsWith("2::")) webSocket.send("2::")
                if (text == "1::") {
                    val initialized = webSocket.send("1::/com.samsung.companion") &&
                        initializeCompanion(webSocket)
                    if (!initialized || !webSocket.send(commandMessage(key, duid))) {
                        if (continuation.isActive) continuation.resumeWithException(
                            IllegalStateException("TV-kommandot kunde inte köas"),
                        )
                        return
                    }
                    if (continuation.isActive) continuation.resume(webSocket)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (continuation.isActive) continuation.resumeWithException(
                    IllegalStateException("TV:n stängde anslutningen före kommandot"),
                )
            }

            override fun onFailure(webSocket: WebSocket, throwable: Throwable, response: Response?) {
                if (continuation.isActive) continuation.resumeWithException(throwable)
            }
        }
        socket = client.newWebSocket(
            Request.Builder().url("ws://$host:8000/socket.io/1/websocket/$socketId").build(),
            listener,
        )
        continuation.invokeOnCancellation { socket.cancel() }
    }

    private fun initializeCompanion(webSocket: WebSocket): Boolean {
        val secondTvRegistered = webSocket.send(encryptedEvent("registerPush", JsonObject().apply {
            addProperty("eventType", "EMP")
            addProperty("plugin", "SecondTV")
        }))
        val remoteRegistered = webSocket.send(encryptedEvent("registerPush", JsonObject().apply {
            addProperty("eventType", "EMP")
            addProperty("plugin", "RemoteControl")
        }))
        val duidRequested = webSocket.send(encryptedEvent("callCommon", JsonObject().apply {
            addProperty("method", "POST")
            add("body", JsonObject().apply {
                addProperty("plugin", "NNavi")
                addProperty("api", "GetDUID")
                addProperty("version", "1.000")
            })
        }))
        return secondTvRegistered && remoteRegistered && duidRequested
    }

    internal fun commandMessage(key: String, duid: String): String {
        require(key.matches(Regex("KEY_[A-Z0-9_]+"))) { "Ogiltig knappkod" }
        val command = JsonObject().apply {
            addProperty("method", "POST")
            add("body", JsonObject().apply {
                addProperty("plugin", "RemoteControl")
                addProperty("param1", duid)
                addProperty("param2", "Click")
                addProperty("param3", key)
                addProperty("param4", "false")
                addProperty("api", "SendRemoteKey")
                addProperty("version", "1.000")
            })
        }
        return encryptedEvent("callCommon", command)
    }

    private fun encryptedEvent(name: String, data: JsonObject): String {
        val encrypted = crypto.encryptCommand(identity.aesKey, data.toString())
        val byteList = JsonArray().apply { encrypted.forEach { add(it.toInt() and 0xff) } }.toString()
        val payload = JsonObject().apply {
            addProperty("name", name)
            add("args", JsonArray().apply {
                add(JsonObject().apply {
                    identity.sessionId.toIntOrNull()?.let { addProperty("Session_Id", it) }
                        ?: addProperty("Session_Id", identity.sessionId)
                    addProperty("body", byteList)
                })
            })
        }
        return "$EVENT_PREFIX$payload"
    }

    private companion object {
        const val EVENT_PREFIX = "5::/com.samsung.companion:"
    }
}
