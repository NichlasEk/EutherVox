package se.euther.eutherbeam.androidtv

import java.net.InetSocketAddress
import javax.net.ssl.SSLSocket
import java.security.cert.X509Certificate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import se.euther.eutherbeam.nec.Ipv4Subnet
import java.net.HttpURLConnection
import java.net.URL
import com.google.gson.JsonParser

internal class AndroidTvNetworkDiscovery(private val identity: AndroidTvIdentity) {
    suspend fun discover(subnet: Ipv4Subnet): List<AndroidTvDevice> {
        require(subnet.hostCount in 1..MAXIMUM_SCAN_HOSTS) { "Nätet får innehålla högst $MAXIMUM_SCAN_HOSTS adresser" }
        val devices = mutableListOf<AndroidTvDevice>()
        for (batch in subnet.hosts().chunked(SCAN_BATCH_SIZE)) {
            val found = coroutineScope {
                batch.map { host -> async(Dispatchers.IO) { inspect(host) } }
                    .awaitAll()
                    .filterNotNull()
            }
            devices += found
        }
        return devices
    }

    suspend fun inspect(host: String): AndroidTvDevice? = withContext(Dispatchers.IO) {
        val remoteName = runCatching {
            val socket = identity.sslContext().socketFactory.createSocket() as SSLSocket
            socket.use {
                it.connect(InetSocketAddress(host, AndroidTvProtocol.PAIRING_PORT), CONNECT_TIMEOUT_MS)
                it.soTimeout = HANDSHAKE_TIMEOUT_MS
                it.startHandshake()
                val certificate = it.session.peerCertificates.firstOrNull() as? X509Certificate
                    ?: return@runCatching null
                nameFrom(certificate) ?: "Android TV"
            }
        }.getOrNull()
        val cast = inspectCast(host)
        if (remoteName == null && cast == null) return@withContext null
        AndroidTvDevice(
            id = cast?.id ?: "ip:$host",
            name = cast?.name ?: remoteName ?: "Android TV",
            address = host,
            model = cast?.model ?: "Android TV",
            supportsRemote = remoteName != null,
            supportsCast = cast != null,
        )
    }

    private fun inspectCast(host: String): CastInfo? = runCatching {
        val connection = URL("http://$host:8008/setup/eureka_info?options=detail").openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = HANDSHAKE_TIMEOUT_MS
        connection.inputStream.bufferedReader().use { reader ->
            val json = JsonParser.parseReader(reader).asJsonObject
            CastInfo(
                id = "cast:${json.get("ssdp_udn")?.asString?.removePrefix("uuid:") ?: json.get("device_info")?.asJsonObject?.get("cloud_device_id")?.asString ?: host}",
                name = json.get("name")?.asString ?: json.get("device_info")?.asJsonObject?.get("name")?.asString ?: "Google TV",
                model = json.get("device_info")?.asJsonObject?.get("model_name")?.asString ?: "Google TV",
            )
        }
    }.getOrNull()

    private fun nameFrom(certificate: X509Certificate): String? {
        val commonName = certificate.subjectX500Principal.name
            .split(',')
            .firstOrNull { it.trim().startsWith("CN=") }
            ?.trim()
            ?.removePrefix("CN=")
            .orEmpty()
        if (commonName.isBlank()) return null
        val parts = commonName.split('/')
        return parts.getOrNull(parts.lastIndex - 1)?.takeIf { it.isNotBlank() }
            ?: commonName.takeIf { it != "atvremote" }
    }

    private companion object {
        const val MAXIMUM_SCAN_HOSTS = 1024L
        const val SCAN_BATCH_SIZE = 24
        const val CONNECT_TIMEOUT_MS = 350
        const val HANDSHAKE_TIMEOUT_MS = 1_500
    }

    private data class CastInfo(val id: String, val name: String, val model: String)
}
