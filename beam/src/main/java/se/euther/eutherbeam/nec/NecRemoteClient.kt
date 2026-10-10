package se.euther.eutherbeam.nec

import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

internal class NecRemoteClient {
    suspend fun sendPower(host: String, on: Boolean): ByteArray = send(host, if (on) NecProtocol.POWER_ON else NecProtocol.POWER_OFF)

    suspend fun sendInput(host: String, code: String): ByteArray = send(host, NecProtocol.buildSetPacket(code))

    suspend fun discover(subnet: Ipv4Subnet): String? {
        require(subnet.hostCount in 1..MAXIMUM_SCAN_HOSTS) { "Nätet får innehålla högst $MAXIMUM_SCAN_HOSTS adresser" }
        for (batch in subnet.hosts().chunked(SCAN_BATCH_SIZE)) {
            val found = coroutineScope {
                batch.map { host -> async(Dispatchers.IO) { host.takeIf { isVerifiedDisplay(it) } } }
                    .awaitAll()
                    .firstOrNull { it != null }
            }
            if (found != null) return found
        }
        return null
    }

    suspend fun isVerifiedDisplay(host: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val response = exchange(host, NecProtocol.POWER_STATUS_QUERY, CONNECT_TIMEOUT_MS, VERIFY_TIMEOUT_MS)
            NecProtocol.isValidPowerStatusResponse(response)
        }.getOrDefault(false)
    }

    private suspend fun send(host: String, packet: ByteArray): ByteArray = withContext(Dispatchers.IO) {
        exchange(host, packet, COMMAND_TIMEOUT_MS, COMMAND_TIMEOUT_MS)
    }

    private fun exchange(host: String, packet: ByteArray, connectTimeout: Int, readTimeout: Int): ByteArray {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, NecProtocol.PORT), connectTimeout)
            socket.soTimeout = readTimeout
            socket.getOutputStream().run { write(packet); flush() }
            val output = ArrayList<Byte>(128)
            val buffer = ByteArray(128)
            try {
                while (output.size < 1024) {
                    val read = socket.getInputStream().read(buffer)
                    if (read <= 0) break
                    repeat(read) { output += buffer[it] }
                    if (buffer.take(read).contains(0x0d.toByte())) break
                }
            } catch (_: SocketTimeoutException) {
                if (output.isEmpty()) throw IllegalStateException("NEC-skärmen svarade inte")
            }
            if (output.isEmpty()) throw IllegalStateException("NEC-skärmen gav inget svar")
            return output.toByteArray()
        }
    }

    private companion object {
        const val MAXIMUM_SCAN_HOSTS = 1024L
        const val SCAN_BATCH_SIZE = 32
        const val CONNECT_TIMEOUT_MS = 300
        const val VERIFY_TIMEOUT_MS = 900
        const val COMMAND_TIMEOUT_MS = 1_500
    }
}
