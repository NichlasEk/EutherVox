package se.euther.eutherbeam.discovery

import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object SamsungMacResolver {
    suspend fun resolve(address: String): String? = withContext(Dispatchers.IO) {
        primeNeighborCache(address)
        readProcArp(address) ?: readIpNeighbor(address)
    }

    private fun primeNeighborCache(address: String) {
        runCatching {
            DatagramSocket().use { socket ->
                val payload = byteArrayOf(0)
                socket.send(DatagramPacket(payload, payload.size, InetAddress.getByName(address), 9))
            }
        }
    }

    private fun readProcArp(address: String): String? = runCatching {
        File("/proc/net/arp").useLines { lines ->
            lines.drop(1).mapNotNull { parseNeighborLine(it, address) }.firstOrNull()
        }
    }.getOrNull()

    private fun readIpNeighbor(address: String): String? = runCatching {
        val process = ProcessBuilder("ip", "neigh", "show", address).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        parseNeighborLine(output, address)
    }.getOrNull()

    internal fun parseNeighborLine(line: String, address: String): String? {
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.firstOrNull() != address) return null
        val lladdrIndex = parts.indexOf("lladdr")
        val candidate = if (lladdrIndex >= 0) parts.getOrNull(lladdrIndex + 1) else parts.getOrNull(3)
        candidate ?: return null
        return WakeOnLan.normalizeMac(candidate)
    }
}
