package se.euther.eutherbeam.nec

import java.net.Inet4Address
import java.net.InetAddress

internal data class Ipv4Subnet(val network: Long, val prefixLength: Int) {
    val addressCount: Long = 1L shl (32 - prefixLength)
    val hostCount: Long = if (prefixLength <= 30) addressCount - 2 else addressCount

    fun hosts(): Sequence<String> = sequence {
        val first = if (prefixLength <= 30) network + 1 else network
        val last = if (prefixLength <= 30) network + addressCount - 2 else network + addressCount - 1
        for (address in first..last) yield(toAddress(address))
    }

    fun broadcastAddress(): String = toAddress(network + addressCount - 1)

    override fun toString(): String = "${toAddress(network)}/$prefixLength"

    companion object {
        fun fromAddress(address: Inet4Address, prefixLength: Int): Ipv4Subnet {
            require(prefixLength in 0..32)
            val value = address.address.fold(0L) { current, byte -> (current shl 8) or (byte.toLong() and 0xff) }
            val mask = if (prefixLength == 0) 0L else 0xffffffffL shl (32 - prefixLength) and 0xffffffffL
            return Ipv4Subnet(value and mask, prefixLength)
        }

        fun normalizeAddress(value: String): String? = runCatching {
            val trimmed = value.trim()
            if (!trimmed.matches(Regex("(?:\\d{1,3}\\.){3}\\d{1,3}"))) return null
            val address = InetAddress.getByName(trimmed)
            if (address !is Inet4Address || address.isAnyLocalAddress || address.isLoopbackAddress ||
                address.address[0].toInt() and 0xff !in 1..223
            ) null else address.hostAddress
        }.getOrNull()

        private fun toAddress(value: Long): String = listOf(24, 16, 8, 0)
            .joinToString(".") { shift -> ((value shr shift) and 0xff).toString() }
    }
}
