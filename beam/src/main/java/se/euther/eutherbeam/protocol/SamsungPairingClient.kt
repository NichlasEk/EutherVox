package se.euther.eutherbeam.protocol

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class SamsungIdentity(
    val sessionId: String,
    val aesKey: ByteArray,
)

class SamsungPairingClient(
    private val host: String,
    private val appId: String,
    private val deviceId: String,
    private val userId: String,
    private val crypto: SamsungHSeriesCrypto = SamsungHSeriesCrypto(),
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build(),
) {
    suspend fun requestPin() = withContext(Dispatchers.IO) {
        execute(
            Request.Builder()
                .url("http://$host:8080/ws/apps/CloudPINPage")
                .post("pin4".toRequestBody(TEXT))
                .build(),
        )
        execute(
            Request.Builder()
                .url(pairingUrl(step = 0) + "&type=1")
                .get()
                .build(),
        )
    }

    suspend fun confirmPin(pin: String): SamsungIdentity = withContext(Dispatchers.IO) {
        val hello = crypto.generateServerHello(userId, pin)
        val step1Payload = JsonObject().apply {
            add("auth_Data", JsonObject().apply {
                addProperty("auth_type", "SPC")
                addProperty("GeneratorServerHello", hello.message)
            })
        }.toString()
        val step1 = parseAuthData(
            execute(
                Request.Builder()
                    .url(pairingUrl(step = 1))
                    .post(step1Payload.toRequestBody(JSON))
                    .build(),
            ),
        )
        val requestId = step1.requiredString("request_id")
        val clientHello = step1.requiredString("GeneratorClientHello")
        val secret = crypto.parseClientHello(clientHello, hello, userId)
            ?: error("Fel PIN eller ogiltigt svar från TV:n")

        val step2Payload = JsonObject().apply {
            add("auth_Data", JsonObject().apply {
                addProperty("auth_type", "SPC")
                addProperty("request_id", requestId)
                addProperty("ServerAckMsg", crypto.generateServerAcknowledge(secret))
            })
        }.toString()
        val step2 = parseAuthData(
            execute(
                Request.Builder()
                    .url(pairingUrl(step = 2))
                    .post(step2Payload.toRequestBody(JSON))
                    .build(),
            ),
        )
        val clientAck = step2.requiredString("ClientAckMsg")
        check(crypto.verifyClientAcknowledge(clientAck, secret)) { "TV:ns kvittens kunde inte verifieras" }
        val sessionId = step2.requiredString("session_id")

        runCatching {
            execute(
                Request.Builder()
                    .url("http://$host:8080/ws/apps/CloudPINPage/run")
                    .delete()
                    .build(),
            )
        }
        SamsungIdentity(sessionId, secret.aesKey)
    }

    private fun pairingUrl(step: Int): String =
        "http://$host:8080/ws/pairing?step=$step&app_id=$appId&device_id=$deviceId"

    private fun execute(request: Request): String = client.newCall(request).execute().use { response ->
        val body = response.body?.string().orEmpty()
        check(response.isSuccessful) { "TV:n svarade HTTP ${response.code}" }
        body
    }

    private fun parseAuthData(body: String): JsonObject {
        val root = JsonParser.parseString(body).asJsonObject
        val value = root.get("auth_data") ?: root.get("auth_Data") ?: error("TV-svaret saknar auth_data")
        return if (value.isJsonPrimitive) JsonParser.parseString(value.asString).asJsonObject else value.asJsonObject
    }

    private fun JsonObject.requiredString(name: String): String =
        get(name)?.asString?.takeIf(String::isNotBlank) ?: error("TV-svaret saknar $name")

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        val TEXT = "text/plain; charset=utf-8".toMediaType()
    }
}
