package se.euther.euthervox.network

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class EutherAuthClient {
    suspend fun login(websocketUrl: String, username: String, password: String): String = withContext(Dispatchers.IO) {
        require(username.isNotBlank()) { "Ange EutherOxide-användare" }
        require(password.isNotBlank()) { "Ange lösenord första gången" }
        val connection = URL("${httpOrigin(websocketUrl)}/api/app/login").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 5_000
            connection.readTimeout = 10_000
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            val body = Gson().toJson(mapOf("username" to username.trim(), "password" to password))
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode != 200) error("EutherOxide-inloggningen avvisades (${connection.responseCode})")
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            JsonParser.parseString(response).asJsonObject["token"]?.asString
                ?.takeIf(String::isNotBlank)
                ?: error("EutherOxide skickade ingen app-token")
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        internal fun httpOrigin(websocketUrl: String): String {
            val uri = URI(websocketUrl.trim())
            val scheme = when (uri.scheme) {
                "wss" -> "https"
                "ws" -> "http"
                else -> error("Serveradressen måste börja med ws:// eller wss://")
            }
            require(!uri.host.isNullOrBlank()) { "Serveradressen saknar värdnamn" }
            val port = if (uri.port >= 0) ":${uri.port}" else ""
            return "$scheme://${uri.host}$port"
        }
    }
}

/** Stores only the persistent app token; username is harmless and passwords are never persisted. */
class AuthTokenStore(context: Context) {
    private val preferences = context.getSharedPreferences("euthervox_auth", Context.MODE_PRIVATE)

    fun save(token: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val payload = cipher.iv + encrypted
        preferences.edit().putString(TOKEN_KEY, Base64.encodeToString(payload, Base64.NO_WRAP)).apply()
    }

    fun load(): String? = runCatching {
        val encoded = preferences.getString(TOKEN_KEY, null) ?: return null
        val payload = Base64.decode(encoded, Base64.NO_WRAP)
        require(payload.size > IV_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, payload.copyOfRange(0, IV_BYTES)))
        cipher.doFinal(payload.copyOfRange(IV_BYTES, payload.size)).toString(Charsets.UTF_8)
    }.getOrElse {
        clear()
        null
    }

    fun clear() {
        preferences.edit().remove(TOKEN_KEY).apply()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val KEY_ALIAS = "euthervox.eutheroxide.app-token"
        const val TOKEN_KEY = "encrypted_app_token"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
