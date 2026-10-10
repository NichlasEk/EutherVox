package se.euther.eutherbeam.discovery

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal object WakeOnLan {
    fun fromSamsungIdentifier(value: String): String? {
        val suffix = Regex("([0-9A-Fa-f]{12})$").find(value.trim())?.groupValues?.get(1) ?: return null
        return normalizeMac(suffix)
    }

    fun normalizeMac(value: String): String? {
        val compact = value.trim().replace(Regex("[-:.]"), "").uppercase()
        if (!compact.matches(Regex("[0-9A-F]{12}")) || compact == "000000000000") return null
        return compact.chunked(2).joinToString(":")
    }

    fun magicPacket(mac: String): ByteArray {
        val normalized = requireNotNull(normalizeMac(mac)) { "Ogiltig MAC-adress" }
        val bytes = normalized.split(':').map { it.toInt(16).toByte() }.toByteArray()
        return ByteArray(6) { 0xff.toByte() } + ByteArray(16 * bytes.size) { bytes[it % bytes.size] }
    }

    suspend fun send(
        mac: String,
        directedBroadcast: String? = null,
        rememberedAddress: String? = null,
    ) = withContext(Dispatchers.IO) {
        val packet = magicPacket(mac)
        val destinations = buildSet {
            add("255.255.255.255")
            directedBroadcast?.let(::add)
            rememberedAddress?.let(::add)
        }
        DatagramSocket().use { socket ->
            socket.broadcast = true
            repeat(8) { round ->
                destinations.forEach { destination ->
                    listOf(9, 7).forEach { port ->
                        socket.send(DatagramPacket(packet, packet.size, InetAddress.getByName(destination), port))
                    }
                }
                if (round < 7) delay(250)
            }
        }
    }
}
