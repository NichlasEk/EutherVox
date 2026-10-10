package se.euther.eutherbeam.androidtv

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

internal class AndroidTvIdentity {
    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    fun ensureExists() {
        if (keyStore.containsAlias(KEY_ALIAS)) return
        val now = System.currentTimeMillis()
        val specification = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY or KeyProperties.PURPOSE_DECRYPT,
        )
            .setKeySize(2048)
            .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
            .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE, KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
            .setCertificateSubject(X500Principal("CN=EutherBeam"))
            .setCertificateSerialNumber(BigInteger.valueOf(1000))
            .setCertificateNotBefore(Date(now - 86_400_000L))
            .setCertificateNotAfter(Date(now + TEN_YEARS_MS))
            .setUserAuthenticationRequired(false)
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE).run {
            initialize(specification)
            generateKeyPair()
        }
    }

    fun certificate(): X509Certificate {
        ensureExists()
        return keyStore.getCertificate(KEY_ALIAS) as X509Certificate
    }

    fun sslContext(): SSLContext {
        ensureExists()
        val certificate = certificate()
        val privateKey = keyStore.getKey(KEY_ALIAS, null) as PrivateKey
        val context = SSLContext.getInstance("TLS")
        context.init(
            arrayOf<KeyManager>(FixedKeyManager(KEY_ALIAS, privateKey, arrayOf(certificate))),
            arrayOf<TrustManager>(TrustEveryAndroidTvCertificate),
            null,
        )
        return context
    }

    private class FixedKeyManager(
        private val alias: String,
        private val privateKey: PrivateKey,
        private val chain: Array<X509Certificate>,
    ) : X509KeyManager {
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out java.security.Principal>?, socket: java.net.Socket?) = alias
        override fun getClientAliases(keyType: String?, issuers: Array<out java.security.Principal>?) = arrayOf(alias)
        override fun chooseServerAlias(keyType: String?, issuers: Array<out java.security.Principal>?, socket: java.net.Socket?) = null
        override fun getServerAliases(keyType: String?, issuers: Array<out java.security.Principal>?) = null
        override fun getCertificateChain(requestedAlias: String?) = chain
        override fun getPrivateKey(requestedAlias: String?) = privateKey
    }

    private object TrustEveryAndroidTvCertificate : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "eutherbeam_android_tv_remote_v3"
        const val TEN_YEARS_MS = 10L * 365 * 24 * 60 * 60 * 1000
    }
}
