package se.euther.eutherbeam

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.euther.eutherbeam.discovery.SamsungTvDevice
import se.euther.eutherbeam.discovery.SsdpSamsungDiscovery
import se.euther.eutherbeam.discovery.SamsungDeviceStore
import se.euther.eutherbeam.discovery.SamsungMacResolver
import se.euther.eutherbeam.discovery.WakeOnLan
import se.euther.eutherbeam.androidtv.AndroidTvDevice
import se.euther.eutherbeam.androidtv.AndroidTvDeviceStore
import se.euther.eutherbeam.androidtv.AndroidTvIdentity
import se.euther.eutherbeam.androidtv.AndroidTvKey
import se.euther.eutherbeam.androidtv.AndroidTvNetworkDiscovery
import se.euther.eutherbeam.androidtv.AndroidTvPairingClient
import se.euther.eutherbeam.androidtv.AndroidTvRemoteClient
import se.euther.eutherbeam.androidtv.CastCecWakeClient
import se.euther.eutherbeam.nec.Ipv4Subnet
import se.euther.eutherbeam.nec.NecNetworkDiscovery
import se.euther.eutherbeam.nec.NecRemoteClient
import se.euther.eutherbeam.protocol.SamsungIdentity
import se.euther.eutherbeam.protocol.SamsungPairingClient
import se.euther.eutherbeam.protocol.SamsungRemoteSession
import se.euther.eutherbeam.protocol.SamsungIdentityStore
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

private enum class RemoteTab { SAMSUNG, NEC, ANDROID_TV }

internal val BeamDark: Color @Composable get() = MaterialTheme.colorScheme.background
internal val BeamPanel: Color @Composable get() = MaterialTheme.colorScheme.surface
internal val BeamRaised: Color @Composable get() = MaterialTheme.colorScheme.surfaceContainerHigh
internal val BeamOrange: Color @Composable get() = MaterialTheme.colorScheme.tertiary
internal val BeamMint: Color @Composable get() = MaterialTheme.colorScheme.primary
internal val BeamYellow: Color @Composable get() = MaterialTheme.colorScheme.secondary
internal val BeamText: Color @Composable get() = MaterialTheme.colorScheme.onSurface
internal val BeamMuted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant

@Composable
fun EutherBeamPanel() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val discovery = remember { SsdpSamsungDiscovery(context) }
    var scanning by remember { mutableStateOf(false) }
    var devices by remember { mutableStateOf<List<SamsungTvDevice>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var scanGeneration by remember { mutableStateOf(0) }
    var awaitingPinFor by remember { mutableStateOf<SamsungTvDevice?>(null) }
    var pin by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var identity by remember { mutableStateOf<SamsungIdentity?>(null) }
    var actionStatus by remember { mutableStateOf<String?>(null) }
    var samsungWakeGeneration by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val preferences = remember { context.getSharedPreferences("eutherbeam", android.content.Context.MODE_PRIVATE) }
    val samsungDeviceStore = remember { SamsungDeviceStore(context) }
    var savedSamsungDevice by remember { mutableStateOf(samsungDeviceStore.load()) }
    var samsungMac by remember { mutableStateOf(preferences.getString("samsung_tv_mac", "").orEmpty()) }
    var samsungMacInput by remember { mutableStateOf(samsungMac) }
    var selectedTab by remember {
        mutableStateOf(
            when (preferences.getString("selected_remote_tab", "samsung")) {
                "nec" -> RemoteTab.NEC
                "android_tv" -> RemoteTab.ANDROID_TV
                else -> RemoteTab.SAMSUNG
            },
        )
    }
    val necClient = remember { NecRemoteClient() }
    var necAddress by remember { mutableStateOf(preferences.getString("nec_display_ip", "").orEmpty()) }
    var necAddressInput by remember { mutableStateOf(necAddress) }
    var necWorking by remember { mutableStateOf(false) }
    var necStatus by remember { mutableStateOf<String?>(null) }
    val androidTvIdentity = remember { AndroidTvIdentity() }
    val androidTvDeviceStore = remember { AndroidTvDeviceStore(context) }
    val androidTvDiscovery = remember { AndroidTvNetworkDiscovery(androidTvIdentity) }
    val androidTvPairingClient = remember { AndroidTvPairingClient(androidTvIdentity) }
    val androidTvRemoteClient = remember { AndroidTvRemoteClient(androidTvIdentity) }
    val castCecWakeClient = remember { CastCecWakeClient(androidTvIdentity.sslContext()) }
    var androidTvDevices by remember { mutableStateOf(androidTvDeviceStore.load()) }
    var selectedAndroidTvId by remember {
        mutableStateOf(androidTvDeviceStore.selectedId() ?: androidTvDevices.firstOrNull()?.id)
    }
    val initiallySelectedAndroidTv = androidTvDevices.firstOrNull { it.id == selectedAndroidTvId } ?: androidTvDevices.firstOrNull()
    var androidTvAddress by remember { mutableStateOf(initiallySelectedAndroidTv?.address.orEmpty()) }
    var androidTvAddressInput by remember { mutableStateOf(androidTvAddress) }
    var androidTvName by remember { mutableStateOf(initiallySelectedAndroidTv?.name ?: "Android TV") }
    var androidTvPairedHost by remember { mutableStateOf(initiallySelectedAndroidTv?.takeIf { it.paired }?.address.orEmpty()) }
    var androidTvPairingSession by remember { mutableStateOf<AndroidTvPairingClient.Session?>(null) }
    var androidTvPin by remember { mutableStateOf("") }
    var androidTvWorking by remember { mutableStateOf(false) }
    var androidTvStatus by remember { mutableStateOf<String?>(null) }
    var linkedDisplay by remember {
        mutableStateOf(
            if (initiallySelectedAndroidTv?.linkedDisplay == "samsung") LinkedDisplay.SAMSUNG
            else LinkedDisplay.NEC,
        )
    }
    val identityStore = remember { SamsungIdentityStore(context) }
    val clientId = remember {
        preferences.getString("client_id", null) ?: UUID.randomUUID().toString().also {
            preferences.edit().putString("client_id", it).apply()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            androidTvPairingSession?.close()
            androidTvRemoteClient.close()
        }
    }

    LaunchedEffect(scanGeneration, selectedTab) {
        if (selectedTab != RemoteTab.SAMSUNG) return@LaunchedEffect
        scanning = true
        error = null
        val fallbackAddresses = NecNetworkDiscovery.activeSubnet(context)
            ?.hosts()
            ?.take(1_022)
            ?.toList()
            .orEmpty()
        runCatching {
            discovery.discover(
                rememberedAddress = savedSamsungDevice?.address,
                fallbackAddresses = fallbackAddresses,
            )
        }
            .onSuccess { devices = it }
            .onFailure { error = it.message ?: "Nätverkssökningen misslyckades" }
        scanning = false
    }

    LaunchedEffect(devices, savedSamsungDevice?.deviceId) {
        val onlineDevice = devices.firstOrNull()
        if (onlineDevice != null) {
            savedSamsungDevice = onlineDevice
            samsungDeviceStore.save(onlineDevice)
        }
        val device = onlineDevice ?: savedSamsungDevice ?: return@LaunchedEffect
        if (samsungMac.isBlank()) {
            val embeddedMac = device.macAddress ?: WakeOnLan.fromSamsungIdentifier(device.deviceId)
            val resolved = embeddedMac ?: onlineDevice?.let { SamsungMacResolver.resolve(it.address) }
            resolved?.let {
                samsungMac = it
                samsungMacInput = it
                preferences.edit().putString("samsung_tv_mac", it).apply()
                actionStatus = "Samsung-TV:ns MAC-adress sparades automatiskt"
            }
        }
        identity = identityStore.load(device.deviceId)
    }

    val saveNecAddress: () -> Unit = {
        val normalized = Ipv4Subnet.normalizeAddress(necAddressInput)
        if (normalized == null) {
            necStatus = "Ange en giltig IPv4-adress"
        } else {
            necAddress = normalized
            necAddressInput = normalized
            preferences.edit().putString("nec_display_ip", normalized).apply()
            necStatus = "NEC-adressen är sparad"
        }
    }
    val discoverNec: () -> Unit = discover@{
        val subnet = NecNetworkDiscovery.activeSubnet(context)
        if (subnet == null) {
            necStatus = "Kunde inte läsa telefonens aktiva IPv4-nät"
            return@discover
        }
        if (subnet.hostCount !in 1..1024) {
            necStatus = "$subnet är för stort. Ange skärmens IP manuellt."
            return@discover
        }
        necWorking = true
        necStatus = "Söker efter verifierad NEC-skärm på $subnet…"
        scope.launch {
            runCatching { necClient.discover(subnet) ?: error("Ingen NEC-skärm hittades på $subnet") }
                .onSuccess { found ->
                    necAddress = found
                    necAddressInput = found
                    preferences.edit().putString("nec_display_ip", found).apply()
                    necStatus = "NEC-skärm hittad och sparad: $found"
                }
                .onFailure { necStatus = it.message ?: "NEC-sökningen misslyckades" }
            necWorking = false
        }
    }
    val sendNecPower: (Boolean) -> Unit = sendPower@{ on ->
        val host = Ipv4Subnet.normalizeAddress(necAddress)
        if (host == null) {
            necStatus = "Spara eller hitta NEC-skärmens IP först"
            return@sendPower
        }
        necWorking = true
        necStatus = if (on) "Slår på NEC-skärmen…" else "Stänger av NEC-skärmen…"
        scope.launch {
            runCatching { necClient.sendPower(host, on) }
                .onSuccess { necStatus = if (on) "NEC: ström på skickad" else "NEC: ström av skickad" }
                .onFailure { necStatus = it.message ?: "NEC-kommandot misslyckades" }
            necWorking = false
        }
    }
    val sendNecInput: (String) -> Unit = sendInput@{ code ->
        val host = Ipv4Subnet.normalizeAddress(necAddress)
        if (host == null) {
            necStatus = "Spara eller hitta NEC-skärmens IP först"
            return@sendInput
        }
        necWorking = true
        necStatus = "Skickar NEC-kod $code…"
        scope.launch {
            runCatching { necClient.sendInput(host, code) }
                .onSuccess { necStatus = "NEC-kod $code skickad" }
                .onFailure { necStatus = it.message ?: "NEC-kommandot misslyckades" }
            necWorking = false
        }
    }

    val saveSamsungMac: () -> Unit = saveMac@{
        val normalized = WakeOnLan.normalizeMac(samsungMacInput)
        if (normalized == null) {
            actionStatus = "Ange en giltig MAC-adress, exempelvis 12:34:56:78:9A:BC"
            return@saveMac
        }
        samsungMac = normalized
        samsungMacInput = normalized
        preferences.edit().putString("samsung_tv_mac", normalized).apply()
        actionStatus = "Samsung-TV:ns MAC-adress är sparad"
    }
    val wakeSamsung: () -> Unit = wake@{
        val normalizedMac = WakeOnLan.normalizeMac(samsungMacInput)
        val cecPuck = androidTvDevices.firstOrNull { it.linkedDisplay == "samsung" }
        if (normalizedMac == null && cecPuck == null) {
            actionStatus = "Koppla en Android TV-puck till Samsung eller spara TV:ns MAC-adress först"
            return@wake
        }
        normalizedMac?.let {
            samsungMac = it
            samsungMacInput = it
            preferences.edit().putString("samsung_tv_mac", it).apply()
        }
        samsungWakeGeneration += 1
        val wakeGeneration = samsungWakeGeneration
        working = true
        actionStatus = if (cecPuck != null) "Skickar Cast direkt till ${cecPuck.name}…" else "Skickar Wake-on-LAN till Samsung-TV:n…"
        scope.launch wakeLaunch@{
            val broadcast = NecNetworkDiscovery.activeSubnet(context)?.broadcastAddress()
            val remembered = devices.firstOrNull() ?: savedSamsungDevice
            val castResult = cecPuck?.let { puck ->
                runCatching { castCecWakeClient.wake(puck.address, puck.castPort) }
            }
            normalizedMac?.let { WakeOnLan.send(it, broadcast, remembered?.address) }

            val savedIdentity = identity
            if (cecPuck == null && remembered != null && savedIdentity != null) {
                withTimeoutOrNull(2_500) {
                    runCatching {
                        SamsungRemoteSession(remembered.address, savedIdentity, remembered.deviceId)
                            .sendKey("KEY_POWERON")
                    }
                }
            }

            working = false
            actionStatus = when {
                castResult?.isSuccess == true -> "Cast skickad till ${cecPuck?.name}. HDMI-CEC bör väcka Samsung; du kan fortsätta använda appen."
                cecPuck != null -> "Cast till ${cecPuck.name} misslyckades: ${castResult?.exceptionOrNull()?.message ?: "okänt fel"}. WoL skickades som reserv."
                else -> "Wake-on-LAN skickad. Kontrollerar Samsung i bakgrunden."
            }

            val address = remembered?.address ?: return@wakeLaunch
            val found = withTimeoutOrNull(25_000) {
                while (samsungWakeGeneration == wakeGeneration) {
                    runCatching {
                        discovery.discoverAddress(address)?.let { return@withTimeoutOrNull it }
                    }
                    delay(900)
                }
                null
            }
            if (found != null && samsungWakeGeneration == wakeGeneration) {
                devices = listOf(found)
                savedSamsungDevice = found
                samsungDeviceStore.save(found)
                actionStatus = "Samsung-TV:n är vaken och återansluten"
            }
        }
    }

    val discoverAndroidTv: () -> Unit = discover@{
        val subnet = NecNetworkDiscovery.activeSubnet(context)
        if (subnet == null) {
            androidTvStatus = "Kunde inte läsa telefonens aktiva IPv4-nät"
            return@discover
        }
        if (subnet.hostCount !in 1..1024) {
            androidTvStatus = "$subnet är för stort. Ange puckens IP manuellt."
            return@discover
        }
        androidTvWorking = true
        androidTvStatus = "Söker efter Android TV Remote Service på $subnet…"
        scope.launch {
            runCatching { androidTvDiscovery.discover(subnet).ifEmpty { error("Ingen Android TV-puck hittades på $subnet") } }
                .onSuccess { foundDevices ->
                    androidTvDevices = androidTvDeviceStore.merge(androidTvDevices, foundDevices)
                    val found = androidTvDevices.firstOrNull { it.id == selectedAndroidTvId }
                        ?: foundDevices.firstOrNull { it.name.equals("Bakdörr", ignoreCase = true) }
                        ?: foundDevices.first()
                    selectedAndroidTvId = found.id
                    androidTvAddress = found.address
                    androidTvAddressInput = found.address
                    androidTvName = found.name
                    androidTvPairedHost = found.takeIf { it.paired }?.address.orEmpty()
                    linkedDisplay = if (found.linkedDisplay == "samsung") LinkedDisplay.SAMSUNG else LinkedDisplay.NEC
                    androidTvDeviceStore.save(androidTvDevices, found.id)
                    androidTvStatus = "${foundDevices.size} puck${if (foundDevices.size == 1) "" else "ar"} hittade. ${found.name} är vald."
                }
                .onFailure { androidTvStatus = it.message ?: "Android TV-sökningen misslyckades" }
            androidTvWorking = false
        }
    }
    val startAndroidTvPairing: () -> Unit = startPairing@{
        val host = Ipv4Subnet.normalizeAddress(androidTvAddressInput)
        if (host == null) {
            androidTvStatus = "Ange eller hitta en giltig IPv4-adress först"
            return@startPairing
        }
        androidTvWorking = true
        androidTvStatus = "Öppnar krypterad parning med $host…"
        scope.launch {
            runCatching { androidTvPairingClient.start(host) }
                .onSuccess { session ->
                    androidTvPairingSession?.close()
                    androidTvPairingSession = session
                    androidTvAddress = host
                    androidTvAddressInput = host
                    val existing = androidTvDevices.firstOrNull { it.address == host }
                    if (existing == null) {
                        val manual = AndroidTvDevice("ip:$host", "Android TV", host, supportsRemote = true)
                        androidTvDevices = androidTvDevices + manual
                        selectedAndroidTvId = manual.id
                    } else {
                        selectedAndroidTvId = existing.id
                    }
                    androidTvDeviceStore.save(androidTvDevices, selectedAndroidTvId)
                    androidTvStatus = "Skriv in den sexteckniga koden från Android TV:n"
                }
                .onFailure { androidTvStatus = it.message ?: "Kunde inte starta Android TV-parningen" }
            androidTvWorking = false
        }
    }
    val sendAndroidTvKey: (AndroidTvKey) -> Unit = sendAndroidKey@{ key ->
        val host = Ipv4Subnet.normalizeAddress(androidTvAddress)
        if (host == null || androidTvPairedHost != host) {
            androidTvStatus = "Para Android TV-pucken först"
            return@sendAndroidKey
        }
        androidTvWorking = true
        androidTvStatus = "Skickar ${key.name.lowercase()}…"
        scope.launch {
            var usedCastFallback = false
            val selectedPuck = androidTvDevices.firstOrNull { it.id == selectedAndroidTvId }
            runCatching {
                if (
                    key in setOf(AndroidTvKey.POWER, AndroidTvKey.WAKEUP) &&
                    selectedPuck != null &&
                    !androidTvRemoteClient.isAvailable(host)
                ) {
                    castCecWakeClient.wake(selectedPuck.address, selectedPuck.castPort)
                    usedCastFallback = true
                } else {
                    androidTvRemoteClient.sendKey(host, key)
                }
            }
                .recoverCatching { remoteError ->
                    if (key !in setOf(AndroidTvKey.POWER, AndroidTvKey.WAKEUP) || selectedPuck == null) {
                        throw remoteError
                    }
                    castCecWakeClient.wake(selectedPuck.address, selectedPuck.castPort)
                    usedCastFallback = true
                }
                .onSuccess {
                    androidTvStatus = if (usedCastFallback) {
                        "${androidTvName} väcks via Cast"
                    } else {
                        "${key.name.lowercase()} skickad"
                    }
                }
                .onFailure { androidTvStatus = it.message ?: "Android TV-kommandot misslyckades" }
            androidTvWorking = false
        }
    }
    val wakeAndroidTvPlayer: () -> Unit = wakePlayer@{
        val puck = androidTvDevices.firstOrNull { it.id == selectedAndroidTvId }
        if (puck == null) {
            androidTvStatus = "Välj en sparad Android TV-puck först"
            return@wakePlayer
        }
        androidTvWorking = true
        androidTvStatus = "Väcker ${puck.name} via Cast…"
        scope.launch {
            runCatching { castCecWakeClient.wake(puck.address, puck.castPort) }
                .onSuccess { androidTvStatus = "${puck.name} är väckt. Android Remote återansluter när den är redo." }
                .onFailure { androidTvStatus = it.message ?: "Kunde inte väcka ${puck.name}" }
            androidTvWorking = false
        }
    }
    val setRoomPower: (Boolean) -> Unit = roomPower@{ on ->
        val host = Ipv4Subnet.normalizeAddress(androidTvAddress)
        val selectedPuck = androidTvDevices.firstOrNull { it.id == selectedAndroidTvId }
        if (host == null || selectedPuck == null) {
            androidTvStatus = "Välj en sparad Android TV-puck först"
            return@roomPower
        }
        if (androidTvPairedHost != host && !on) {
            androidTvStatus = "Para Android TV-pucken först"
            return@roomPower
        }
        androidTvWorking = true
        androidTvStatus = if (on) "Startar vardagsrummet…" else "Stänger vardagsrummet…"
        scope.launch {
            runCatching {
                if (on) {
                    var castWakeSucceeded = false
                    when (linkedDisplay) {
                        LinkedDisplay.NEC -> {
                            val displayHost = Ipv4Subnet.normalizeAddress(necAddress)
                                ?: error("Hitta eller spara NEC-skärmen först")
                            necClient.sendPower(displayHost, true)
                            castWakeSucceeded = runCatching {
                                castCecWakeClient.wake(selectedPuck.address, selectedPuck.castPort)
                            }.isSuccess
                        }
                        LinkedDisplay.SAMSUNG -> {
                            if (!selectedPuck.supportsCast) error("Den valda pucken saknar Cast/CEC-väckning")
                            castCecWakeClient.wake(selectedPuck.address, selectedPuck.castPort)
                            castWakeSucceeded = true
                        }
                    }
                    delay(750)
                    if (androidTvPairedHost == host) {
                        val remoteWake = runCatching { androidTvRemoteClient.sendKey(host, AndroidTvKey.WAKEUP) }
                        if (!castWakeSucceeded) remoteWake.getOrThrow()
                    } else if (!castWakeSucceeded) {
                        error("Pucken svarade varken på Cast eller en parad Android Remote-kanal")
                    }
                } else {
                    androidTvRemoteClient.sendKey(host, AndroidTvKey.SLEEP)
                    delay(350)
                    when (linkedDisplay) {
                        LinkedDisplay.NEC -> {
                            val displayHost = Ipv4Subnet.normalizeAddress(necAddress)
                                ?: error("Hitta eller spara NEC-skärmen först")
                            necClient.sendPower(displayHost, false)
                        }
                        LinkedDisplay.SAMSUNG -> {
                            val device = devices.firstOrNull() ?: error("Samsung-TV:n hittades inte")
                            val savedIdentity = identity ?: error("Samsung-TV:n är inte parad")
                            SamsungRemoteSession(device.address, savedIdentity, device.deviceId).sendKey("KEY_POWEROFF")
                        }
                    }
                }
            }.onSuccess {
                androidTvStatus = if (on) "Vardagsrummet är startat" else "Allt är avstängt"
            }.onFailure { androidTvStatus = it.message ?: "Rumsscenen misslyckades" }
            androidTvWorking = false
        }
    }

    MaterialTheme {
        Surface(color = BeamDark, modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 22.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Box(Modifier.size(9.dp).background(BeamOrange, CircleShape))
                    Text("EUTHERBEAM // LAN CONTROL", color = BeamMint, fontWeight = FontWeight.Black, fontSize = 13.sp)
                }
                Text("Dina skärmar. Ett tryck bort.", color = BeamText, fontWeight = FontWeight.Bold, fontSize = 30.sp)
                Text("Lokal signal. Krypterad länk. Ingen molntjänst.", color = BeamMuted, fontSize = 15.sp)

                LogitechVolumeCard()
                RemoteTabs(
                    selected = selectedTab,
                    onSelect = { tab ->
                        selectedTab = tab
                        preferences.edit().putString("selected_remote_tab", tab.name.lowercase()).apply()
                    },
                )

                when (selectedTab) {
                    RemoteTab.SAMSUNG -> {
                    val onlineSamsung = devices.firstOrNull()
                    val displayedSamsung = onlineSamsung ?: savedSamsungDevice
                    when {
                        displayedSamsung != null -> DeviceCard(
                            device = displayedSamsung,
                            online = onlineSamsung != null,
                            paired = identity != null,
                            working = working,
                            onPair = pair@{
                                val device = onlineSamsung ?: return@pair
                                working = true
                                actionStatus = "Ber TV:n visa en PIN…"
                                scope.launch {
                                    runCatching {
                                        SamsungPairingClient(device.address, "12345", clientId, "654321").requestPin()
                                    }.onSuccess {
                                        awaitingPinFor = device
                                        actionStatus = "Skriv in PIN-koden från TV:n"
                                    }.onFailure { actionStatus = it.message ?: "Kunde inte starta parning" }
                                    working = false
                                }
                            },
                        )
                        scanning -> ScanningCard()
                        else -> EmptyCard(error)
                    }

                    if (onlineSamsung != null) identity?.let { savedIdentity ->
                        val sendKey: (String) -> Unit = { key ->
                            working = true
                            actionStatus = "Skickar $key…"
                            scope.launch {
                                runCatching {
                                    SamsungRemoteSession(onlineSamsung.address, savedIdentity, onlineSamsung.deviceId).sendKey(key)
                                }
                                    .onSuccess { actionStatus = "$key skickad" }
                                    .onFailure { actionStatus = it.message ?: "Kommandot misslyckades" }
                                working = false
                            }
                        }
                        NavigationCard(working = working, onKey = sendKey)
                        RemoteCard(working = working, onKey = sendKey)
                    }
                    SamsungWakeCard(
                        device = displayedSamsung,
                        online = onlineSamsung != null,
                        cecPuckName = androidTvDevices.firstOrNull { it.linkedDisplay == "samsung" }?.name,
                        macInput = samsungMacInput,
                        onMacChange = { samsungMacInput = it },
                        scanning = scanning,
                        working = working,
                        onSaveMac = saveSamsungMac,
                        onWake = wakeSamsung,
                    )
                    actionStatus?.let { Text(it, color = BeamMuted, fontSize = 13.sp) }

                    Button(
                        onClick = { scanGeneration++ },
                        enabled = !scanning,
                        colors = ButtonDefaults.buttonColors(containerColor = BeamMint, contentColor = BeamDark),
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                    ) {
                        Text(if (scanning) "Söker…" else "Sök igen", fontWeight = FontWeight.Bold)
                    }
                    }
                    RemoteTab.NEC -> {
                        NecRemotePanel(
                            savedAddress = necAddress,
                            addressInput = necAddressInput,
                            onAddressChange = { necAddressInput = it },
                            status = necStatus,
                            working = necWorking,
                            onSaveAddress = saveNecAddress,
                            onDiscover = discoverNec,
                            onPower = sendNecPower,
                            onInput = sendNecInput,
                        )
                    }
                    RemoteTab.ANDROID_TV -> {
                        AndroidTvPanel(
                            devices = androidTvDevices,
                            selectedDeviceId = selectedAndroidTvId,
                            onSelectDevice = { selected ->
                                selectedAndroidTvId = selected.id
                                androidTvAddress = selected.address
                                androidTvAddressInput = selected.address
                                androidTvName = selected.name
                                androidTvPairedHost = selected.takeIf { it.paired }?.address.orEmpty()
                                linkedDisplay = if (selected.linkedDisplay == "samsung") LinkedDisplay.SAMSUNG else LinkedDisplay.NEC
                                androidTvRemoteClient.disconnect()
                                androidTvDeviceStore.save(androidTvDevices, selected.id)
                                androidTvStatus = "${selected.name} vald"
                            },
                            savedAddress = androidTvAddress,
                            deviceName = androidTvName,
                            addressInput = androidTvAddressInput,
                            onAddressChange = { androidTvAddressInput = it },
                            paired = androidTvAddress.isNotBlank() && androidTvPairedHost == androidTvAddress,
                            linkedDisplay = linkedDisplay,
                            onLinkedDisplayChange = { display ->
                                linkedDisplay = display
                                androidTvDevices = androidTvDevices.map {
                                    when {
                                        it.id == selectedAndroidTvId -> it.copy(linkedDisplay = display.name.lowercase())
                                        it.linkedDisplay == display.name.lowercase() -> it.copy(
                                            linkedDisplay = if (display == LinkedDisplay.SAMSUNG) "nec" else "samsung",
                                        )
                                        else -> it
                                    }
                                }
                                androidTvDeviceStore.save(androidTvDevices, selectedAndroidTvId)
                            },
                            working = androidTvWorking,
                            status = androidTvStatus,
                            onDiscover = discoverAndroidTv,
                            onPair = startAndroidTvPairing,
                            onForget = {
                                androidTvRemoteClient.disconnect()
                                androidTvPairedHost = ""
                                androidTvDevices = androidTvDevices.map {
                                    if (it.id == selectedAndroidTvId) it.copy(paired = false) else it
                                }
                                androidTvDeviceStore.save(androidTvDevices, selectedAndroidTvId)
                                androidTvStatus = "Den lokala kopplingen är glömd. Du kan para igen."
                            },
                            onKey = sendAndroidTvKey,
                            canCastWake = androidTvDevices.any { it.id == selectedAndroidTvId },
                            onWakePlayer = wakeAndroidTvPlayer,
                            onRoomPower = setRoomPower,
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text("Lokal anslutning • Ingen molntjänst", color = BeamMuted, fontSize = 12.sp)
            }
        }
    }

    awaitingPinFor?.let { device ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { if (!working) awaitingPinFor = null },
            title = { Text("Para ${device.friendlyName}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Skriv in den fyrsiffriga koden som visas på TV:n.")
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { value -> pin = value.filter(Char::isDigit).take(4) },
                        label = { Text("PIN") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = pin.length == 4 && !working,
                    onClick = {
                        working = true
                        actionStatus = "Verifierar PIN…"
                        scope.launch {
                            runCatching {
                                SamsungPairingClient(device.address, "12345", clientId, "654321").confirmPin(pin)
                            }.onSuccess { paired ->
                                identity = paired
                                identityStore.save(device.deviceId, paired)
                                awaitingPinFor = null
                                pin = ""
                                actionStatus = "TV:n är parad"
                            }.onFailure { actionStatus = it.message ?: "Parningen misslyckades" }
                            working = false
                        }
                    },
                ) { Text(if (working) "Parar…" else "Bekräfta") }
            },
            dismissButton = {
                Button(enabled = !working, onClick = { awaitingPinFor = null }) { Text("Avbryt") }
            },
        )
    }

    androidTvPairingSession?.let { session ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {
                if (!androidTvWorking) {
                    session.close()
                    androidTvPairingSession = null
                    androidTvPin = ""
                }
            },
            title = { Text("Para Android TV") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Skriv in den sexteckniga hexkoden som visas på Android TV:n.")
                    OutlinedTextField(
                        value = androidTvPin,
                        onValueChange = { value ->
                            androidTvPin = value.uppercase().filter { it.isDigit() || it in 'A'..'F' }.take(6)
                        },
                        label = { Text("PIN") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = androidTvPin.length == 6 && !androidTvWorking,
                    onClick = {
                        androidTvWorking = true
                        androidTvStatus = "Verifierar Android TV-koden…"
                        scope.launch {
                            runCatching { session.finish(androidTvPin) }
                                .onSuccess {
                                    androidTvPairedHost = androidTvAddress
                                    androidTvDevices = androidTvDevices.map {
                                        if (it.id == selectedAndroidTvId || it.address == androidTvAddress) it.copy(paired = true) else it
                                    }
                                    androidTvDeviceStore.save(androidTvDevices, selectedAndroidTvId)
                                    androidTvPairingSession = null
                                    androidTvPin = ""
                                    androidTvStatus = "Android TV-pucken är parad och kopplad till vardagsrummet"
                                }
                                .onFailure {
                                    androidTvPairingSession = null
                                    androidTvPin = ""
                                    androidTvStatus = it.message ?: "Android TV-parningen misslyckades"
                                }
                            androidTvWorking = false
                        }
                    },
                ) { Text(if (androidTvWorking) "Parar…" else "Bekräfta") }
            },
            dismissButton = {
                Button(
                    enabled = !androidTvWorking,
                    onClick = {
                        session.close()
                        androidTvPairingSession = null
                        androidTvPin = ""
                        androidTvStatus = "Parningen avbröts"
                    },
                ) { Text("Avbryt") }
            },
        )
    }
}

@Composable
private fun RemoteTabs(selected: RemoteTab, onSelect: (RemoteTab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BeamPanel, RoundedCornerShape(24.dp))
            .border(BorderStroke(1.dp, BeamMint.copy(alpha = 0.2f)), RoundedCornerShape(24.dp))
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        listOf(
            RemoteTab.SAMSUNG to "Samsung",
            RemoteTab.NEC to "NEC",
            RemoteTab.ANDROID_TV to "Android TV",
        ).forEach { (tab, label) ->
            val active = selected == tab
            Button(
                onClick = { onSelect(tab) },
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(19.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (active) BeamOrange else BeamRaised,
                    contentColor = if (active) BeamDark else BeamText,
                ),
            ) { Text(label, fontWeight = FontWeight.Bold, fontSize = 11.sp) }
        }
    }
}

@Composable
private fun ScanningCard() = BeamCard {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        CircularProgressIndicator(color = BeamMint, modifier = Modifier.size(30.dp), strokeWidth = 3.dp)
        Column {
            Text("Söker på nätverket", color = BeamText, fontWeight = FontWeight.Bold)
            Text("Lyssnar efter Samsung-TV…", color = BeamMuted, fontSize = 13.sp)
        }
    }
}

@Composable
private fun DeviceCard(
    device: SamsungTvDevice,
    online: Boolean,
    paired: Boolean,
    working: Boolean,
    onPair: () -> Unit,
) = BeamCard {
    Text(if (online) "TV ONLINE" else "TV SPARAD // STANDBY", color = if (online) BeamMint else BeamOrange, fontWeight = FontWeight.Black, fontSize = 12.sp)
    Spacer(Modifier.height(10.dp))
    Text(device.friendlyName, color = BeamText, fontWeight = FontWeight.Bold, fontSize = 22.sp)
    Text("${device.modelName}  •  ${device.address}", color = BeamMuted, fontSize = 14.sp)
    Spacer(Modifier.height(18.dp))
    Button(
        onClick = onPair,
        enabled = online && !paired && !working,
        colors = ButtonDefaults.buttonColors(containerColor = BeamMint, contentColor = BeamDark),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            when {
                !online -> "Standby • väck TV:n nedan"
                paired -> "Parad"
                else -> "Para TV"
            },
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun SamsungWakeCard(
    device: SamsungTvDevice?,
    online: Boolean,
    cecPuckName: String?,
    macInput: String,
    onMacChange: (String) -> Unit,
    scanning: Boolean,
    working: Boolean,
    onSaveMac: () -> Unit,
    onWake: () -> Unit,
) = BeamCard {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("NÄTVERKSSTART", color = BeamOrange, fontWeight = FontWeight.Black, fontSize = 12.sp)
            Text(if (online) "TV:n svarar på nätverket" else "Väck TV:n via nätverket", color = BeamText, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            device?.let { Text(it.address, color = BeamMuted, fontSize = 12.sp) }
        }
        if (scanning || working) CircularProgressIndicator(color = BeamOrange, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
    }
    Spacer(Modifier.height(14.dp))
    OutlinedTextField(
        value = macInput,
        onValueChange = { value ->
            onMacChange(value.uppercase().filter { it.isDigit() || it in 'A'..'F' || it == ':' || it == '-' || it == '.' }.take(17))
        },
        label = { Text("Samsung-TV:ns MAC-adress") },
        placeholder = { Text("12:34:56:78:9A:BC") },
        enabled = !working,
        singleLine = true,
        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
            focusedTextColor = BeamText,
            unfocusedTextColor = BeamText,
            focusedBorderColor = BeamOrange,
            unfocusedBorderColor = BeamMint.copy(alpha = 0.5f),
            focusedLabelColor = BeamOrange,
            unfocusedLabelColor = BeamMuted,
            cursorColor = BeamOrange,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = onSaveMac,
            enabled = !working,
            modifier = Modifier.weight(1f).height(52.dp),
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, BeamMint.copy(alpha = 0.5f)),
            colors = ButtonDefaults.buttonColors(containerColor = BeamRaised, contentColor = BeamText),
        ) { Text("Spara MAC", fontWeight = FontWeight.Bold, fontSize = 12.sp) }
        Button(
            onClick = onWake,
            enabled = !working && (cecPuckName != null || WakeOnLan.normalizeMac(macInput) != null),
            modifier = Modifier.weight(1f).height(52.dp),
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, BeamYellow),
            colors = ButtonDefaults.buttonColors(containerColor = BeamOrange, contentColor = BeamDark),
        ) { Text(if (online) "Skicka väcksignal" else "Väck TV", fontWeight = FontWeight.Black, fontSize = 12.sp) }
    }
    Text(
        if (cecPuckName != null) {
            "EutherBeam väcker $cecPuckName via Google Cast. Pucken aktiverar HDMI och startar Samsung-TV:n genom CEC."
        } else {
            "Koppla en Cast-puck till Samsung under Android TV-fliken. Wake-on-LAN skickas som reservväg."
        },
        color = BeamMuted,
        fontSize = 12.sp,
        modifier = Modifier.padding(top = 10.dp),
    )
    if (!online) {
        Text(
            if (cecPuckName != null) "HDMI-CEC måste vara aktivt på TV:n och pucken." else "Den här Samsung-modellen vaknade inte med vanlig Wake-on-LAN i vårt test.",
            color = BeamOrange,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun RemoteCard(working: Boolean, onKey: (String) -> Unit) = BeamCard {
    Text("SNABBKONTROLLER", color = BeamOrange, fontWeight = FontWeight.Black, fontSize = 12.sp)
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RemoteButton("−", "KEY_VOLDOWN", working, Modifier.weight(1f), onKey)
        RemoteButton("Mute", "KEY_MUTE", working, Modifier.weight(1f), onKey)
        RemoteButton("+", "KEY_VOLUP", working, Modifier.weight(1f), onKey)
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RemoteButton("Källa", "KEY_SOURCE", working, Modifier.weight(1f), onKey)
        RemoteButton("Meny", "KEY_MENU", working, Modifier.weight(1f), onKey)
        RemoteButton("Stäng av", "KEY_POWEROFF", working, Modifier.weight(1f), onKey)
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RemoteButton("Kanal −", "KEY_CHDOWN", working, Modifier.weight(1f), onKey)
        RemoteButton("Guide", "KEY_GUIDE", working, Modifier.weight(1f), onKey)
        RemoteButton("Kanal +", "KEY_CHUP", working, Modifier.weight(1f), onKey)
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RemoteButton("⏪", "KEY_REWIND", working, Modifier.weight(1f), onKey)
        RemoteButton("▶", "KEY_PLAY", working, Modifier.weight(1f), onKey)
        RemoteButton("Ⅱ", "KEY_PAUSE", working, Modifier.weight(1f), onKey)
        RemoteButton("⏩", "KEY_FF", working, Modifier.weight(1f), onKey)
    }
}

@Composable
private fun NavigationCard(working: Boolean, onKey: (String) -> Unit) = BeamCard {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("NAVIGATION", color = BeamOrange, fontWeight = FontWeight.Black, fontSize = 12.sp)
        Text("H-SERIES", color = BeamMuted, fontWeight = FontWeight.Bold, fontSize = 11.sp)
    }
    Spacer(Modifier.height(16.dp))
    Box(
        modifier = Modifier
            .size(252.dp)
            .align(Alignment.CenterHorizontally)
            .background(BeamRaised, CircleShape)
            .border(BorderStroke(2.dp, BeamOrange.copy(alpha = 0.72f)), CircleShape),
    ) {
        DpadButton("↑", "KEY_UP", working, Modifier.align(Alignment.TopCenter).offset(y = 8.dp), onKey)
        DpadButton("↓", "KEY_DOWN", working, Modifier.align(Alignment.BottomCenter).offset(y = (-8).dp), onKey)
        DpadButton("←", "KEY_LEFT", working, Modifier.align(Alignment.CenterStart).offset(x = 8.dp), onKey)
        DpadButton("→", "KEY_RIGHT", working, Modifier.align(Alignment.CenterEnd).offset(x = (-8).dp), onKey)
        Button(
            onClick = { onKey("KEY_ENTER") },
            enabled = !working,
            modifier = Modifier.align(Alignment.Center).size(86.dp),
            shape = CircleShape,
            border = BorderStroke(2.dp, BeamYellow),
            colors = ButtonDefaults.buttonColors(
                containerColor = BeamOrange,
                contentColor = BeamDark,
                disabledContainerColor = BeamOrange.copy(alpha = 0.42f),
            ),
        ) { Text("OK", fontWeight = FontWeight.Black, fontSize = 18.sp) }
    }
    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RemoteButton("Tillbaka", "KEY_RETURN", working, Modifier.weight(1f), onKey)
        RemoteButton("Info", "KEY_INFO", working, Modifier.weight(1f), onKey)
        RemoteButton("Avsluta", "KEY_EXIT", working, Modifier.weight(1f), onKey)
    }
}

@Composable
private fun DpadButton(
    label: String,
    key: String,
    working: Boolean,
    modifier: Modifier,
    onKey: (String) -> Unit,
) {
    Button(
        onClick = { onKey(key) },
        enabled = !working,
        modifier = modifier.width(70.dp).height(66.dp),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, BeamMint.copy(alpha = 0.7f)),
        colors = ButtonDefaults.buttonColors(
            containerColor = BeamPanel,
            contentColor = BeamText,
            disabledContainerColor = BeamPanel.copy(alpha = 0.5f),
        ),
    ) { Text(label, fontSize = 27.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun RemoteButton(
    label: String,
    key: String,
    working: Boolean,
    modifier: Modifier,
    onKey: (String) -> Unit,
) {
    Button(
        onClick = { onKey(key) },
        enabled = !working,
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, BeamMint.copy(alpha = 0.45f)),
        colors = ButtonDefaults.buttonColors(
            containerColor = BeamRaised,
            contentColor = BeamText,
            disabledContainerColor = BeamRaised.copy(alpha = 0.5f),
        ),
    ) { Text(label, fontSize = 12.sp) }
}

@Composable
private fun EmptyCard(error: String?) = BeamCard {
    Text("Ingen TV hittades", color = BeamText, fontWeight = FontWeight.Bold, fontSize = 19.sp)
    Text(error ?: "Kontrollera att TV:n och telefonen är på samma nätverk.", color = BeamMuted, fontSize = 14.sp)
}

@Composable
internal fun BeamCard(content: @Composable ColumnScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(BeamPanel, RoundedCornerShape(24.dp))
            .border(BorderStroke(1.dp, BeamMint.copy(alpha = 0.2f)), RoundedCornerShape(24.dp))
            .padding(20.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) { content() }
    }
}
