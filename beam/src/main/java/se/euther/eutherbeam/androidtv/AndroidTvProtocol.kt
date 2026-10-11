package se.euther.eutherbeam.androidtv

internal object AndroidTvProtocol {
    const val REMOTE_PORT = 6466
    const val PAIRING_PORT = 6467
    const val ACTIVE_FEATURES = 611L // existing key/power/volume/app-link controls
    const val MEDIA_FEATURES = 623L // additionally request IME and voice for media queries

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

    fun remoteConfigure(features: Long = ACTIVE_FEATURES): ByteArray = ProtoWire.message {
        nested(1) {
            uint(1, features)
            nested(2) {
                uint(3, 1)
                string(4, "1")
                string(5, "se.euther.eutherbeam")
                string(6, "0.1.0")
            }
        }
    }

    fun remoteSetActive(features: Long = ACTIVE_FEATURES): ByteArray = ProtoWire.message {
        nested(2) { uint(1, features) }
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

    fun voiceBegin(session: Long): ByteArray = ProtoWire.message { nested(30) { uint(1, session) } }
    fun voiceEnd(session: Long): ByteArray = ProtoWire.message { nested(32) { uint(1, session) } }
    fun voicePayload(session: Long, pcm: ByteArray): ByteArray = ProtoWire.message {
        require(pcm.size in 8192..20480 && pcm.size % 2 == 0)
        nested(31) { uint(1, session); bytes(2, pcm) }
    }
    fun voiceSession(payload: ByteArray): Long? = ProtoWire.nested(payload, 30)?.let { ProtoWire.varint(it, 1) ?: 0L }
    fun supportedFeatures(payload: ByteArray): Long? = ProtoWire.nested(payload, 1)?.let { ProtoWire.varint(it, 1) }
    fun currentApp(payload: ByteArray): String? = ProtoWire.nested(payload, 20)?.let { ProtoWire.nested(it, 1) }
        ?.let { info -> ProtoWire.fields(info).firstOrNull { it.number == 12 }?.bytes?.toString(Charsets.UTF_8) }
        ?.takeIf { it.matches(Regex("[a-zA-Z0-9_.]{1,200}")) }

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
    SEARCH(84),
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
    MEDIA_PLAY(126),
    MEDIA_PAUSE(127),
    MEDIA_STOP(86),
    MEDIA_NEXT(87),
    MEDIA_PREVIOUS(88),
    MEDIA_REWIND(89),
    MEDIA_FAST_FORWARD(90),
    VOLUME_MUTE(164),
    SLEEP(223),
    WAKEUP(224),
}
