package se.euther.euthervox.lights

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

data class MagicHomeDevice(
    val ip: String,
    val mac: String,
    val model: String,
    val powerOn: Boolean? = null,
    val colorHex: String? = null,
)

data class MagicHomeWifiUiState(
    val scanning: Boolean = false,
    val busyIp: String? = null,
    val provisioning: Boolean = false,
    val devices: List<MagicHomeDevice> = emptyList(),
    val status: String = "Sök efter Magic Home-moduler på ditt Wi-Fi.",
    val error: String? = null,
)

object MagicHomeProtocol {
    val statusQuery: ByteArray = byteArrayOf(0x81.toByte(), 0x8a.toByte(), 0x8b.toByte(), 0x96.toByte())

    fun parseDiscovery(text: String): MagicHomeDevice? {
        val fields = text.trim().split(',').map(String::trim)
        if (fields.size < 3 || !isIpv4(fields[0])) return null
        return MagicHomeDevice(ip = fields[0], mac = fields[1].uppercase(), model = fields[2])
    }

    fun power(on: Boolean): ByteArray = withChecksum(
        byteArrayOf(0x71, if (on) 0x23 else 0x24, 0x0f),
    )

    fun color(red: Int, green: Int, blue: Int): ByteArray = withChecksum(
        byteArrayOf(0x31, red.clampByte(), green.clampByte(), blue.clampByte(), 0x00, 0xf0.toByte(), 0x0f),
    )

    val effects: Map<String, Int> = linkedMapOf(
        "Regnbåge mjuk" to 0x25,
        "Regnbåge blink" to 0x30,
        "Röd blink" to 0x31,
        "Grön blink" to 0x32,
        "Blå blink" to 0x33,
        "Lila blink" to 0x36,
        "Vit blink" to 0x37,
        "Regnbåge hopp" to 0x38,
    )

    fun effect(label: String, speed: Int): ByteArray {
        val code = effects[label] ?: error("Okänt mönster")
        val safeSpeed = speed.coerceIn(1, 100)
        val delay = ((100 - safeSpeed) * 30 / 100) + 1
        return withChecksum(byteArrayOf(0x61, code.toByte(), delay.toByte(), 0x0f))
    }

    fun parseStatus(ip: String, mac: String, model: String, bytes: ByteArray): MagicHomeDevice? {
        if (bytes.size < 12 || bytes[0] != 0x81.toByte()) return null
        val powerOn = when (bytes[2].toInt() and 0xff) {
            0x23 -> true
            0x24 -> false
            else -> null
        }
        val color = "#%02X%02X%02X".format(
            bytes[6].toInt() and 0xff,
            bytes[7].toInt() and 0xff,
            bytes[8].toInt() and 0xff,
        )
        return MagicHomeDevice(ip, mac, model, powerOn, color)
    }

    fun provisioningCommands(ssid: String, password: String): List<String> = listOf(
        "AT+WMODE=STA\r",
        "AT+WSSSID=$ssid\r",
        "AT+WSKEY=WPA2PSK,AES,$password\r",
        "AT+Z\r",
        "AT+Z\r",
    )

    fun validateWifiCredentials(ssid: String, password: String): String? = when {
        ssid.isBlank() -> "Ange Wi-Fi-nätets namn."
        ssid.length > 32 -> "Nätverksnamnet får vara högst 32 tecken."
        ssid.any { it == '\r' || it == '\n' || it == ',' } -> "Nätverksnamnet innehåller ett tecken som modulen inte klarar."
        password.length !in 8..63 -> "WPA2-lösenordet måste vara 8–63 tecken."
        password.any { it == '\r' || it == '\n' || it == ',' } -> "Lösenordet innehåller ett tecken som modulen inte klarar."
        else -> null
    }

    private fun withChecksum(payload: ByteArray): ByteArray = payload + byteArrayOf(
        (payload.sumOf { it.toInt() and 0xff } and 0xff).toByte(),
    )

    private fun Int.clampByte(): Byte = coerceIn(0, 255).toByte()

    private fun isIpv4(value: String): Boolean {
        val parts = value.split('.')
        return parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }
    }
}

class MagicHomeWifiController(context: Context, private val scope: CoroutineScope) {
    private val appContext = context.applicationContext
    private val wifiManager = appContext.getSystemService(WifiManager::class.java)
    private val mutableState = MutableStateFlow(MagicHomeWifiUiState())
    val state: StateFlow<MagicHomeWifiUiState> = mutableState.asStateFlow()
    private var activeJob: Job? = null

    fun discover() {
        activeJob?.cancel()
        activeJob = scope.launch {
            mutableState.value = mutableState.value.copy(scanning = true, error = null, status = "Söker i fyra sekunder…")
            val result = runCatching { discoverOnLan() }
            result.onSuccess { devices ->
                mutableState.value = mutableState.value.copy(
                    scanning = false,
                    devices = devices,
                    status = if (devices.isEmpty()) "Inga moduler hittades. Kontrollera att telefonen är på samma Wi-Fi."
                    else "Hittade ${devices.size} Magic Home-modul${if (devices.size == 1) "" else "er"}.",
                )
                devices.forEach { refresh(it) }
            }.onFailure { error ->
                mutableState.value = mutableState.value.copy(scanning = false, error = "Sökningen misslyckades: ${error.safeMessage()}")
            }
        }
    }

    fun refresh(device: MagicHomeDevice) = runDeviceAction(device, "Läser status…") {
        queryStatus(device)
    }

    fun setPower(device: MagicHomeDevice, on: Boolean) = runDeviceAction(
        device,
        if (on) "Tänder…" else "Släcker…",
    ) {
        sendCommand(device.ip, MagicHomeProtocol.power(on))
        queryStatus(device).copy(powerOn = on)
    }

    fun setColor(device: MagicHomeDevice, red: Int, green: Int, blue: Int) = runDeviceAction(device, "Byter färg…") {
        sendCommand(device.ip, MagicHomeProtocol.color(red, green, blue))
        queryStatus(device).copy(powerOn = true, colorHex = "#%02X%02X%02X".format(red, green, blue))
    }

    fun setColorBrightness(device: MagicHomeDevice, red: Int, green: Int, blue: Int, brightness: Int) =
        runDeviceAction(device, "Ställer exakt färg…") {
            val level = brightness.coerceIn(1, 100) / 100f
            val scaledRed = (red.coerceIn(0, 255) * level).toInt()
            val scaledGreen = (green.coerceIn(0, 255) * level).toInt()
            val scaledBlue = (blue.coerceIn(0, 255) * level).toInt()
            sendCommand(device.ip, MagicHomeProtocol.color(scaledRed, scaledGreen, scaledBlue))
            queryStatus(device).copy(
                powerOn = true,
                colorHex = "#%02X%02X%02X".format(red, green, blue),
            )
        }

    fun setEffect(device: MagicHomeDevice, label: String, speed: Int) = runDeviceAction(device, "Startar mönster…") {
        sendCommand(device.ip, MagicHomeProtocol.power(true))
        sendCommand(device.ip, MagicHomeProtocol.effect(label, speed))
        queryStatus(device).copy(powerOn = true)
    }

    fun provision(ssid: String, password: String, onFinished: () -> Unit) {
        val validation = MagicHomeProtocol.validateWifiCredentials(ssid, password)
        if (validation != null) {
            mutableState.value = mutableState.value.copy(error = validation)
            return
        }
        activeJob?.cancel()
        activeJob = scope.launch {
            mutableState.value = mutableState.value.copy(
                provisioning = true,
                error = null,
                status = "Skickar nätverksinställning direkt till modulen…",
            )
            val result = runCatching { provisionAccessPoint(ssid, password) }
            mutableState.value = if (result.isSuccess) {
                mutableState.value.copy(
                    provisioning = false,
                    status = "Inställningen skickades. Modulen startar om; anslut telefonen till hemmets Wi-Fi och sök igen.",
                )
            } else {
                mutableState.value.copy(
                    provisioning = false,
                    error = "Kunde inte konfigurera modulen: ${result.exceptionOrNull()?.safeMessage()}",
                )
            }
            onFinished()
        }
    }

    fun close() {
        activeJob?.cancel()
        activeJob = null
    }

    private fun runDeviceAction(
        device: MagicHomeDevice,
        status: String,
        action: suspend () -> MagicHomeDevice,
    ) {
        scope.launch {
            mutableState.value = mutableState.value.copy(busyIp = device.ip, status = status, error = null)
            runCatching { action() }.onSuccess(::updateDevice).onFailure { error ->
                mutableState.value = mutableState.value.copy(error = "${device.ip}: ${error.safeMessage()}")
            }
            mutableState.value = mutableState.value.copy(busyIp = null)
        }
    }

    private fun updateDevice(updated: MagicHomeDevice) {
        mutableState.value = mutableState.value.copy(
            devices = mutableState.value.devices.map { if (it.ip == updated.ip) updated else it },
            status = "${updated.model} på ${updated.ip} svarar.",
            error = null,
        )
    }

    private suspend fun discoverOnLan(): List<MagicHomeDevice> = withContext(Dispatchers.IO) {
        val found = linkedMapOf<String, MagicHomeDevice>()
        val lock = wifiManager?.createMulticastLock("euthervox-magic-home")?.apply {
            setReferenceCounted(false)
            acquire()
        }
        try {
            DatagramSocket(null).use { socket ->
                socket.reuseAddress = true
                socket.broadcast = true
                socket.soTimeout = 450
                socket.bind(InetSocketAddress(0))
                val request = "HF-A11ASSISTHREAD".toByteArray(Charsets.US_ASCII)
                listOf("255.255.255.255", wifiBroadcastAddress()).distinct().forEach { target ->
                    val packet = DatagramPacket(request, request.size, InetAddress.getByName(target), DISCOVERY_PORT)
                    socket.send(packet)
                }
                val deadline = System.currentTimeMillis() + 4_000
                while (System.currentTimeMillis() < deadline) {
                    try {
                        val bytes = ByteArray(256)
                        val packet = DatagramPacket(bytes, bytes.size)
                        socket.receive(packet)
                        MagicHomeProtocol.parseDiscovery(String(packet.data, 0, packet.length, Charsets.US_ASCII))
                            ?.let { found[it.ip] = it }
                    } catch (_: java.net.SocketTimeoutException) {
                        // Keep listening until the shared deadline.
                    }
                }
            }
        } finally {
            if (lock?.isHeld == true) lock.release()
        }
        found.values.sortedBy { it.ip }
    }

    @Suppress("DEPRECATION")
    private fun wifiBroadcastAddress(): String {
        val dhcp = wifiManager?.dhcpInfo ?: return "255.255.255.255"
        val broadcast = (dhcp.ipAddress and dhcp.netmask) or dhcp.netmask.inv()
        return listOf(0, 8, 16, 24).joinToString(".") { ((broadcast shr it) and 0xff).toString() }
    }

    private suspend fun queryStatus(device: MagicHomeDevice): MagicHomeDevice = withContext(Dispatchers.IO) {
        val response = sendCommand(device.ip, MagicHomeProtocol.statusQuery, expectReply = true)
        MagicHomeProtocol.parseStatus(device.ip, device.mac, device.model, response)
            ?: error("modulen gav ett okänt statussvar")
    }

    private suspend fun sendCommand(ip: String, bytes: ByteArray, expectReply: Boolean = false): ByteArray = withContext(Dispatchers.IO) {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(ip, CONTROL_PORT), 2_000)
            socket.soTimeout = 2_000
            socket.getOutputStream().write(bytes)
            socket.getOutputStream().flush()
            if (!expectReply) return@use ByteArray(0)
            val response = ByteArray(64)
            val count = socket.getInputStream().read(response)
            if (count <= 0) error("tomt svar")
            response.copyOf(count)
        }
    }

    private suspend fun provisionAccessPoint(ssid: String, password: String) = withContext(Dispatchers.IO) {
        DatagramSocket(null).use { socket ->
            socket.reuseAddress = true
            socket.broadcast = true
            socket.soTimeout = 2_000
            socket.bind(InetSocketAddress(0))
            val discovery = "HF-A11ASSISTHREAD".toByteArray(Charsets.US_ASCII)
            socket.send(
                DatagramPacket(discovery, discovery.size, InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT),
            )
            val discoveryReply = receiveProvisioningReply(socket)
                ?: error("modulens tillfälliga Wi-Fi svarade inte")
            val address = discoveryReply.address
            val enterAtMode = "+ok".toByteArray(Charsets.US_ASCII)
            socket.send(DatagramPacket(enterAtMode, enterAtMode.size, address, DISCOVERY_PORT))
            receiveProvisioningReply(socket)

            var acknowledgements = 0
            for (command in MagicHomeProtocol.provisioningCommands(ssid, password)) {
                val bytes = command.toByteArray(Charsets.US_ASCII)
                socket.send(DatagramPacket(bytes, bytes.size, address, DISCOVERY_PORT))
                val reply = receiveProvisioningReply(socket)
                if (reply != null) {
                    val answer = String(reply.data, 0, reply.length, Charsets.US_ASCII)
                    if (answer.contains("ERR", ignoreCase = true)) error("modulen avvisade ett inställningssteg")
                    if (answer.contains("+ok", ignoreCase = true)) acknowledgements++
                }
            }
            if (acknowledgements == 0) error("modulen kvitterade inte nätverksinställningen")
        }
    }

    private fun receiveProvisioningReply(socket: DatagramSocket): DatagramPacket? = try {
        val response = ByteArray(256)
        DatagramPacket(response, response.size).also(socket::receive)
    } catch (_: java.net.SocketTimeoutException) {
        null
    }

    private fun Throwable.safeMessage(): String = when (this) {
        is java.net.SocketTimeoutException -> "timeout"
        else -> message?.take(120) ?: javaClass.simpleName
    }

    companion object {
        private const val CONTROL_PORT = 5577
        private const val DISCOVERY_PORT = 48899
    }
}
