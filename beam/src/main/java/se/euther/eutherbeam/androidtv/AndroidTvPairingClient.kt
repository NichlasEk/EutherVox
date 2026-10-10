package se.euther.eutherbeam.androidtv

import java.math.BigInteger
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey
import java.security.cert.X509Certificate
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object AndroidTvPairingSecret {
    fun calculate(clientKey: RSAPublicKey, serverKey: RSAPublicKey, pin: String): ByteArray {
        val normalized = pin.trim().uppercase()
        require(normalized.matches(Regex("[0-9A-F]{6}"))) { "PIN-koden ska vara sex hextecken" }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(unsigned(clientKey.modulus))
        digest.update(unsigned(clientKey.publicExponent))
        digest.update(unsigned(serverKey.modulus))
        digest.update(unsigned(serverKey.publicExponent))
        digest.update(hex(normalized.substring(2)))
        return digest.digest().also {
            require((it[0].toInt() and 0xff) == normalized.substring(0, 2).toInt(16)) {
                "PIN-koden stämmer inte med den här parningen"
            }
        }
    }

    internal fun unsigned(value: BigInteger): ByteArray = value.toByteArray().let {
        if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it
    }

    private fun hex(value: String): ByteArray = ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

internal class AndroidTvPairingClient(
    private val identity: AndroidTvIdentity,
    private val clientName: String = "EutherBeam",
) {
    suspend fun start(host: String): Session = withContext(Dispatchers.IO) {
        identity.ensureExists()
        val socket = identity.sslContext().socketFactory.createSocket() as SSLSocket
        try {
            socket.connect(InetSocketAddress(host, AndroidTvProtocol.PAIRING_PORT), CONNECT_TIMEOUT_MS)
            socket.soTimeout = PAIRING_TIMEOUT_MS
            socket.startHandshake()
            val serverCertificate = socket.session.peerCertificates.firstOrNull() as? X509Certificate
                ?: error("Android TV skickade inget TLS-certifikat")

            socket.send(AndroidTvProtocol.pairingRequest(clientName))
            socket.expectPairingField(11)
            socket.send(AndroidTvProtocol.pairingOptions())
            socket.expectPairingField(20)
            socket.send(AndroidTvProtocol.pairingConfiguration())
            socket.expectPairingField(31)
            Session(socket, identity.certificate(), serverCertificate)
        } catch (throwable: Throwable) {
            runCatching { socket.close() }
            throw throwable
        }
    }

    internal class Session(
        private val socket: SSLSocket,
        private val clientCertificate: X509Certificate,
        private val serverCertificate: X509Certificate,
    ) : AutoCloseable {
        suspend fun finish(pin: String) = withContext(Dispatchers.IO) {
            try {
                val clientKey = clientCertificate.publicKey as? RSAPublicKey ?: error("Klientnyckeln är inte RSA")
                val serverKey = serverCertificate.publicKey as? RSAPublicKey ?: error("Android TV-nyckeln är inte RSA")
                val secret = AndroidTvPairingSecret.calculate(clientKey, serverKey, pin)
                socket.send(AndroidTvProtocol.pairingSecret(secret))
                socket.expectPairingField(41)
            } finally {
                close()
            }
        }

        override fun close() = runCatching { socket.close() }.let { Unit }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 4_000
        const val PAIRING_TIMEOUT_MS = 90_000
    }
}

private fun SSLSocket.send(message: ByteArray) = ProtoWire.frame(outputStream, message)

private fun SSLSocket.expectPairingField(expected: Int) {
    val actual = AndroidTvProtocol.pairingField(ProtoWire.readFrame(inputStream))
    require(actual == expected) { "Oväntat Android TV-parningssvar: ${actual ?: "tomt"}, väntade $expected" }
}
