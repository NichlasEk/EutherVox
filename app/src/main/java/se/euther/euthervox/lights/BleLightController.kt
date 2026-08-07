package se.euther.euthervox.lights

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

enum class BleLightProtocol(val label: String) {
    Triones("LED BLE / Triones (FFD5)"),
    Surplife("Surplife / Magic Home BLE (F000)"),
    Candidate("Möjlig LED-kontroller"),
    Unknown("Okänd BLE-enhet"),
}

data class BleLightDevice(
    val address: String,
    val name: String,
    val rssi: Int,
    val advertisedServices: List<String>,
    val protocol: BleLightProtocol,
)

data class BleGattCharacteristicInfo(
    val serviceUuid: String,
    val characteristicUuid: String,
    val properties: String,
)

data class BleLightUiState(
    val supported: Boolean = true,
    val bluetoothEnabled: Boolean = true,
    val scanning: Boolean = false,
    val devices: List<BleLightDevice> = emptyList(),
    val inspectingAddress: String? = null,
    val inspectedDevice: BleLightDevice? = null,
    val characteristics: List<BleGattCharacteristicInfo> = emptyList(),
    val status: String = "Tryck på Sök efter slingor.",
    val error: String? = null,
) {
    fun diagnosticText(): String {
        val device = inspectedDevice ?: return "Ingen BLE-enhet har undersökts."
        return buildString {
            appendLine("EutherVox BLE light diagnostic")
            appendLine("name=${device.name}")
            appendLine("address=${device.address}")
            appendLine("rssi=${device.rssi}")
            appendLine("guess=${device.protocol.label}")
            appendLine("advertised_services=${device.advertisedServices.joinToString()}")
            characteristics.forEach {
                appendLine("service=${it.serviceUuid} characteristic=${it.characteristicUuid} properties=${it.properties}")
            }
        }.trim()
    }
}

object BleLightProtocolClassifier {
    private val likelyNames = listOf(
        "lednet", "ble-led", "ledble", "ledblue", "triones", "dream", "qhm",
        "elk-bledom", "magic home", "magichome", "surplife", "happylighting",
    )

    fun classify(name: String, serviceUuids: Collection<String>): BleLightProtocol {
        val services = serviceUuids.map { it.lowercase(Locale.ROOT) }
        return when {
            services.any { it.startsWith("0000ffd5-") || it == "ffd5" } -> BleLightProtocol.Triones
            services.any { it.startsWith("0000f000-") || it == "f000" } -> BleLightProtocol.Surplife
            likelyNames.any { name.lowercase(Locale.ROOT).contains(it) } -> BleLightProtocol.Candidate
            else -> BleLightProtocol.Unknown
        }
    }
}

fun requiredBlePermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
} else {
    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
}

fun hasBlePermissions(context: Context): Boolean = requiredBlePermissions().all {
    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
}

class BleLightController(context: Context, private val scope: CoroutineScope) {
    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val mutableState = MutableStateFlow(BleLightUiState())
    val state: StateFlow<BleLightUiState> = mutableState.asStateFlow()
    private val found = linkedMapOf<String, BleLightDevice>()
    private val nativeDevices = mutableMapOf<String, android.bluetooth.BluetoothDevice>()
    private var scanJob: Job? = null
    private var activeGatt: BluetoothGatt? = null

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val advertised = result.scanRecord?.serviceUuids.orEmpty().map { it.uuid.toString() }
            val name = result.device.name ?: result.scanRecord?.deviceName ?: "Namnlös BLE-enhet"
            val device = BleLightDevice(
                address = result.device.address,
                name = name,
                rssi = result.rssi,
                advertisedServices = advertised,
                protocol = BleLightProtocolClassifier.classify(name, advertised),
            )
            found[device.address] = device
            nativeDevices[device.address] = result.device
            publishDevices()
        }

        override fun onScanFailed(errorCode: Int) {
            mutableState.value = mutableState.value.copy(
                scanning = false,
                error = "BLE-skanningen misslyckades med kod $errorCode.",
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (!hasBlePermissions(appContext)) {
            mutableState.value = mutableState.value.copy(error = "Ge appen behörighet till enheter i närheten.")
            return
        }
        val adapter = bluetoothManager?.adapter
        if (adapter == null) {
            mutableState.value = BleLightUiState(supported = false, status = "Telefonen saknar Bluetooth.")
            return
        }
        if (!adapter.isEnabled) {
            mutableState.value = mutableState.value.copy(
                bluetoothEnabled = false,
                error = "Slå på Bluetooth och försök igen.",
            )
            return
        }
        stopScan()
        found.clear()
        nativeDevices.clear()
        mutableState.value = BleLightUiState(scanning = true, status = "Söker i 12 sekunder…")
        adapter.bluetoothLeScanner?.startScan(scanCallback) ?: run {
            mutableState.value = mutableState.value.copy(scanning = false, error = "BLE-skannern är inte tillgänglig.")
            return
        }
        scanJob = scope.launch {
            delay(12_000)
            stopScan()
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        if (hasBlePermissions(appContext)) {
            bluetoothManager?.adapter?.bluetoothLeScanner?.runCatching { stopScan(scanCallback) }
        }
        if (mutableState.value.scanning) {
            mutableState.value = mutableState.value.copy(
                scanning = false,
                status = if (found.isEmpty()) "Inga BLE-enheter hittades." else "Välj slingan och tryck Undersök.",
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun inspect(device: BleLightDevice) {
        if (!hasBlePermissions(appContext)) return
        stopScan()
        activeGatt?.close()
        activeGatt = null
        val native = nativeDevices[device.address]
        if (native == null) {
            mutableState.value = mutableState.value.copy(error = "Enheten måste hittas i en ny skanning.")
            return
        }
        mutableState.value = mutableState.value.copy(
            inspectingAddress = device.address,
            inspectedDevice = device,
            characteristics = emptyList(),
            status = "Ansluter för att läsa GATT-tjänster…",
            error = null,
        )
        activeGatt = native.connectGatt(appContext, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS && newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                    gatt.discoverServices()
                } else if (newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) {
                    if (mutableState.value.inspectingAddress != null) {
                        mutableState.value = mutableState.value.copy(
                            inspectingAddress = null,
                            error = "BLE-anslutningen stängdes (status $status).",
                        )
                    }
                    gatt.close()
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    mutableState.value = mutableState.value.copy(
                        inspectingAddress = null,
                        error = "Kunde inte läsa GATT-tjänster (status $status).",
                    )
                    gatt.disconnect()
                    return
                }
                val infos = gatt.services.flatMap(::characteristicInfos)
                val serviceUuids = gatt.services.map { it.uuid.toString() }
                val updated = device.copy(protocol = BleLightProtocolClassifier.classify(device.name, serviceUuids))
                mutableState.value = mutableState.value.copy(
                    inspectingAddress = null,
                    inspectedDevice = updated,
                    characteristics = infos,
                    status = "GATT-fingeravtrycket är klart. Kopiera diagnostiken och skicka den till mig.",
                    error = null,
                )
                gatt.disconnect()
            }
        })
    }

    @SuppressLint("MissingPermission")
    fun close() {
        stopScan()
        activeGatt?.runCatching {
            disconnect()
            close()
        }
        activeGatt = null
    }

    private fun publishDevices() {
        val devices = found.values.sortedWith(
            compareBy<BleLightDevice> { it.protocol == BleLightProtocol.Unknown }.thenByDescending { it.rssi }
        )
        mutableState.value = mutableState.value.copy(devices = devices, status = "Hittade ${devices.size} BLE-enheter…")
    }

    private fun characteristicInfos(service: BluetoothGattService): List<BleGattCharacteristicInfo> =
        service.characteristics.map { characteristic ->
            BleGattCharacteristicInfo(
                serviceUuid = shortUuid(service.uuid),
                characteristicUuid = shortUuid(characteristic.uuid),
                properties = propertyNames(characteristic.properties),
            )
        }

    private fun shortUuid(uuid: UUID): String {
        val text = uuid.toString().lowercase(Locale.ROOT)
        return if (text.endsWith("-0000-1000-8000-00805f9b34fb")) text.substring(4, 8) else text
    }

    private fun propertyNames(properties: Int): String = buildList {
        if (properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("read")
        if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("write")
        if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("write-no-response")
        if (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("notify")
        if (properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("indicate")
    }.joinToString("+").ifEmpty { "none" }
}
