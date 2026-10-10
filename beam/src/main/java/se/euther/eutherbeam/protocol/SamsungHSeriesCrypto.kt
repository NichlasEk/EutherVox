package se.euther.eutherbeam.protocol

import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

data class ServerHello(
    val message: String,
    internal val dataHash: ByteArray,
    internal val pinKey: ByteArray,
)

data class PairingSecret(
    val aesKey: ByteArray,
    internal val skPrime: ByteArray,
)

/** Native implementation of Samsung's SPC exchange used by 2014 H-series TVs. */
class SamsungHSeriesCrypto {
    fun generateServerHello(userId: String, pin: String): ServerHello {
        require(pin.length == 4 && pin.all(Char::isDigit)) { "PIN must contain four digits" }
        val user = userId.toByteArray(Charsets.UTF_8)
        val pinKey = sha1(pin.toByteArray(Charsets.UTF_8)).copyOf(16)
        val encryptedPublicKey = aesCbc(PUBLIC_KEY, pinKey, encrypt = true)
        val transformed = aesEcb(encryptedPublicKey, WHITE_BOX_KEY, encrypt = true)
        val data = int32(user.size) + user + transformed
        val dataHash = sha1(data)
        val message = byteArrayOf(1, 2) + ByteArray(5) + int32(user.size + 132) + data + ByteArray(5)
        return ServerHello(message.toHex(upperCase = true), dataHash, pinKey)
    }

    fun parseClientHello(clientHello: String, hello: ServerHello, ourUserId: String): PairingSecret? {
        val data = clientHello.hexToBytes()
        if (data.size < 168) return null
        val userLength = data.int32At(11)
        val userStart = 15
        val gxStart = userStart + userLength
        val hashStart = gxStart + GX_SIZE
        val flagsStart = hashStart + SHA_DIGEST_LENGTH
        if (userLength < 0 || flagsStart + 5 > data.size) return null

        val clientUser = data.copyOfRange(userStart, gxStart)
        val encryptedGx = data.copyOfRange(gxStart, hashStart)
        val pinEncryptedGx = aesEcb(encryptedGx, WHITE_BOX_KEY, encrypt = false)
        val gx = aesCbc(pinEncryptedGx, hello.pinKey, encrypt = false)
        val secret = BigInteger(1, gx)
            .modPow(PRIVATE_KEY_INTEGER, PRIME_INTEGER)
            .toByteArray()
            .unsigned()

        val expectedSecretHash = data.copyOfRange(hashStart, flagsStart)
        if (!MessageDigest.isEqual(expectedSecretHash, sha1(clientUser + secret))) return null
        if (data[flagsStart] != 0.toByte() || data.int32At(flagsStart + 1) != 0) return null

        val thirdLength = userLength + 132
        val destination = data.copyOfRange(11, 11 + thirdLength) + hello.dataHash
        val destinationHash = sha1(destination)
        val skPrime = sha1(
            clientUser +
                ourUserId.toByteArray(Charsets.UTF_8) +
                gx +
                PUBLIC_KEY +
                secret,
        )
        val skPrimeHash = sha1(skPrime + byteArrayOf(0))
        val contextKey = SamsungWhiteBoxTransform.transform(skPrimeHash)

        // destinationHash participates in Samsung's transcript even though only the context key is retained.
        check(destinationHash.size == SHA_DIGEST_LENGTH)
        return PairingSecret(contextKey, skPrime)
    }

    fun generateServerAcknowledge(secret: PairingSecret): String =
        (ACK_SERVER_PREFIX + sha1(secret.skPrime + byteArrayOf(1)) + ByteArray(5)).toHex(upperCase = true)

    fun verifyClientAcknowledge(clientAck: String, secret: PairingSecret): Boolean {
        val expected = (ACK_CLIENT_PREFIX + sha1(secret.skPrime + byteArrayOf(2)) + ByteArray(5))
            .toHex(upperCase = true)
        return expected.equals(clientAck, ignoreCase = true)
    }

    fun encryptCommand(aesKey: ByteArray, plainText: String): ByteArray {
        val bytes = plainText.toByteArray(Charsets.UTF_8)
        val padding = 16 - (bytes.size % 16)
        return aesEcb(bytes + ByteArray(padding) { padding.toByte() }, aesKey, encrypt = true)
    }

    fun decryptCommand(aesKey: ByteArray, encrypted: ByteArray): String {
        require(encrypted.isNotEmpty() && encrypted.size % 16 == 0) { "Invalid encrypted command" }
        val padded = aesEcb(encrypted, aesKey, encrypt = false)
        val padding = padded.last().toInt() and 0xff
        require(padding in 1..16 && padded.takeLast(padding).all { (it.toInt() and 0xff) == padding }) {
            "Invalid command padding"
        }
        return padded.copyOf(padded.size - padding).toString(Charsets.UTF_8)
    }

    private fun aesCbc(input: ByteArray, key: ByteArray, encrypt: Boolean): ByteArray =
        Cipher.getInstance("AES/CBC/NoPadding").run {
            init(
                if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                IvParameterSpec(ByteArray(16)),
            )
            doFinal(input)
        }

    private fun aesEcb(input: ByteArray, key: ByteArray, encrypt: Boolean): ByteArray =
        Cipher.getInstance("AES/ECB/NoPadding").run {
            init(
                if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
            )
            doFinal(input)
        }

    private fun sha1(input: ByteArray): ByteArray = MessageDigest.getInstance("SHA-1").digest(input)
    private fun int32(value: Int): ByteArray = ByteBuffer.allocate(4).putInt(value).array()
    private fun ByteArray.int32At(offset: Int): Int = ByteBuffer.wrap(this, offset, 4).int

    companion object {
        private const val SHA_DIGEST_LENGTH = 20
        private const val GX_SIZE = 128
        private val ACK_SERVER_PREFIX = "0103000000000000000014".hexToBytes()
        private val ACK_CLIENT_PREFIX = "0104000000000000000014".hexToBytes()
        private val PUBLIC_KEY = "2cb12bb2cbf7cec713c0fff7b59ae68a96784ae517f41d259a45d20556177c0ffe951ca60ec03a990c9412619d1bee30adc7773088c5721664cffcedacf6d251cb4b76e2fd7aef09b3ae9f9496ac8d94ed2b262eee37291c8b237e880cc7c021fb1be0881f3d0bffa4234d3b8e6a61530c00473ce169c025f47fcc001d9b8051".hexToBytes()
        private val PRIVATE_KEY_INTEGER = BigInteger("2fd6334713816fae018cdee4656c5033a8d6b00e8eaea07b3624999242e96247112dcd019c4191f4643c3ce1605002b2e506e7f1d1ef8d9b8044e46d37c0d5263216a87cd783aa185490436c4a0cb2c524e15bc1bfeae703bcbc4b74a0540202e8d79cadaae85c6f9c218bc1107d1f5b4b9bd87160e782f4e436eeb17485ab4d", 16)
        private val PRIME_INTEGER = BigInteger("b361eb0ab01c3439f2c16ffda7b05e3e320701ebee3e249123c3586765fd5bf6c1dfa88bb6bb5da3fde74737cd88b6a26c5ca31d81d18e3515533d08df619317063224cf0943a2f29a5fe60c1c31ddf28334ed76a6478a1122fb24c4a94c8711617ddfe90cf02e643cd82d4748d6d4a7ca2f47d88563aa2baf6482e124acd7dd", 16)
        private val WHITE_BOX_KEY = "abbb120c09e7114243d1fa0102163b27".hexToBytes()
    }
}
