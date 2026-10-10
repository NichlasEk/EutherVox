package se.euther.eutherbeam.androidtv

internal object AndroidTvProtocol {
    const val REMOTE_PORT = 6466
    const val PAIRING_PORT = 6467
    const val ACTIVE_FEATURES = 611L // ping, key, power, volume and app links

    fun pairingRequest(clientName: String): ByteArray = outerPairing(10) {
        string(1, "atvremote")
        string(2, clientName)
    }

    fun pairingOptions(): ByteArray = outerPairing(20) {
        nested(1) {
            uint(1, 3) // hexadecimal
            uint(2, 6) // six symbols
        }
        uint(3, 1) // input role
    }

    fun pairingConfiguration(): ByteArray = outerPairing(30) {
        nested(1) {
            uint(1, 3)
            uint(2, 6)
        }
        uint(2, 1)
    }

    fun pairingSecret(secret: ByteArray): ByteArray = outerPairing(40) { bytes(1, secret) }

    fun pairingField(payload: ByteArray): Int? {
        val fields = ProtoWire.fields(payload)
        val status = fields.firstOrNull { it.number == 2 }?.varint
        require(status == 200L) { "Android TV svarade med parningsstatus ${status ?: "saknas"}" }
        return fields.firstOrNull { it.number in setOf(10, 11, 20, 30, 31, 40, 41) }?.number
    }

    fun remoteConfigure(): ByteArray = ProtoWire.message {
        nested(1) {
            uint(1, ACTIVE_FEATURES)
            nested(2) {
                uint(3, 1)
                string(4, "1")
                string(5, "se.euther.eutherbeam")
                string(6, "0.1.0")
            }
        }
    }

    fun remoteSetActive(): ByteArray = ProtoWire.message {
        nested(2) { uint(1, ACTIVE_FEATURES) }
    }

    fun remotePingResponse(value: Long): ByteArray = ProtoWire.message {
        nested(9) { uint(1, value) }
    }

    fun remoteKey(keyCode: Int): ByteArray = ProtoWire.message {
        nested(10) {
            uint(1, keyCode.toLong())
            uint(2, 3) // short press
        }
    }

    fun topLevelFields(payload: ByteArray): Set<Int> = ProtoWire.fields(payload).mapTo(mutableSetOf()) { it.number }

    fun pingValue(payload: ByteArray): Long? = ProtoWire.nested(payload, 8)?.let { ProtoWire.varint(it, 1) }

    private fun outerPairing(fieldNumber: Int, build: ProtoWire.Writer.() -> Unit): ByteArray = ProtoWire.message {
        uint(1, 2)
        uint(2, 200)
        nested(fieldNumber, build)
    }
}

internal enum class AndroidTvKey(val code: Int) {
    HOME(3),
    BACK(4),
    DPAD_UP(19),
    DPAD_DOWN(20),
    DPAD_LEFT(21),
    DPAD_RIGHT(22),
    DPAD_CENTER(23),
    VOLUME_UP(24),
    VOLUME_DOWN(25),
    POWER(26),
    MEDIA_PLAY_PAUSE(85),
    MEDIA_STOP(86),
    MEDIA_NEXT(87),
    MEDIA_PREVIOUS(88),
    MEDIA_REWIND(89),
    MEDIA_FAST_FORWARD(90),
    VOLUME_MUTE(164),
    SLEEP(223),
    WAKEUP(224),
}
