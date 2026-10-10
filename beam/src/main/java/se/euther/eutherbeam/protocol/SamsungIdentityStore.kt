package se.euther.eutherbeam.protocol

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SamsungIdentityStore(context: Context) {
    private val preferences = context.getSharedPreferences("eutherbeam_sessions", Context.MODE_PRIVATE)

    fun save(deviceId: String, identity: SamsungIdentity) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val plainText = "${identity.sessionId}\n${identity.aesKey.toHex()}".toByteArray(Charsets.UTF_8)
        val encrypted = cipher.iv + cipher.doFinal(plainText)
        preferences.edit().putString(storageKey(deviceId), Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
    }

    fun load(deviceId: String): SamsungIdentity? = runCatching {
        val encoded = preferences.getString(storageKey(deviceId), null) ?: return null
        val encrypted = Base64.decode(encoded, Base64.NO_WRAP)
        require(encrypted.size > IV_SIZE)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, encrypted.copyOfRange(0, IV_SIZE)))
        val plainText = cipher.doFinal(encrypted.copyOfRange(IV_SIZE, encrypted.size)).toString(Charsets.UTF_8)
        val parts = plainText.split('\n', limit = 2)
        SamsungIdentity(parts[0], parts[1].hexToBytes())
    }.getOrNull()

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

    private fun storageKey(deviceId: String): String =
        "identity_v2_${deviceId.replace(Regex("[^A-Za-z0-9_-]"), "_")}"

    private companion object {
        const val KEY_ALIAS = "EutherBeamSamsungIdentity"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}
