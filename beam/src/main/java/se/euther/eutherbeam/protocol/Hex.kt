package se.euther.eutherbeam.protocol

internal fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "Hex data must have an even length" }
    return ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

internal fun ByteArray.toHex(upperCase: Boolean = false): String {
    val value = joinToString("") { "%02x".format(it.toInt() and 0xff) }
    return if (upperCase) value.uppercase() else value
}

internal fun ByteArray.unsigned(): ByteArray =
    if (size > 1 && first() == 0.toByte()) copyOfRange(1, size) else this
