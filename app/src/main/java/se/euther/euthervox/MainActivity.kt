package se.euther.euthervox

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.edit
import se.euther.euthervox.app.Latencies
import se.euther.euthervox.app.VoiceController
import se.euther.euthervox.app.VoiceStatus
import se.euther.euthervox.network.EutherAuthClient
import se.euther.euthervox.lights.BleLightController
import se.euther.euthervox.lights.BleLightDevice
import se.euther.euthervox.lights.BleLightProtocol
import se.euther.euthervox.lights.hasBlePermissions
import se.euther.euthervox.lights.requiredBlePermissions
import se.euther.euthervox.lights.MagicHomeDevice
import se.euther.euthervox.lights.MagicHomeWifiController
import se.euther.euthervox.lights.MagicHomeWifiUiState
import se.euther.euthervox.lights.MagicHomeProtocol
import se.euther.euthervox.protocol.ServerEvent

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { EutherVoxApp() } }
    }
}

private val Forest = Color(0xFF254C3A)
private val Copper = Color(0xFFB86035)
private val Parchment = Color(0xFFF2EBDD)

private data class CharacterUi(val name: String, val symbol: String)

private fun characterUi(id: String) = when (id) {
    "christian-grosshandlare" -> CharacterUi("Christian Grosshandlare", "⚓")
    "sherlock-holmes" -> CharacterUi("Sherlock Holmes", "⌕")
    else -> CharacterUi("Skinnskattaren", "⛏")
}

@Composable
fun EutherVoxApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember { VoiceController(context, scope) }
    val lightController = remember { BleLightController(context, scope) }
    val wifiLightController = remember { MagicHomeWifiController(context, scope) }
    val state by controller.state.collectAsStateWithLifecycle()
    val lightState by lightController.state.collectAsStateWithLifecycle()
    val wifiLightState by wifiLightController.state.collectAsStateWithLifecycle()
    val preferences = remember { context.getSharedPreferences("euthervox", 0) }
    var address by remember { mutableStateOf(preferences.getString("server_address", "").orEmpty()) }
    var nodeName by remember { mutableStateOf(preferences.getString("node_name", "android-phone").orEmpty()) }
    var username by remember { mutableStateOf(preferences.getString("username", "").orEmpty()) }
    var characterId by remember { mutableStateOf(preferences.getString("character_id", "skinnskattaren").orEmpty()) }
    var voiceId by remember { mutableStateOf(preferences.getString("voice_id", "piper-nst").orEmpty()) }
    var settingsAddress by remember { mutableStateOf(address) }
    var settingsNodeName by remember { mutableStateOf(nodeName) }
    var settingsUsername by remember { mutableStateOf(username) }
    var settingsCharacterId by remember { mutableStateOf(characterId) }
    var settingsVoiceId by remember { mutableStateOf(voiceId) }
    var settingsPassword by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(address.isBlank()) }
    var selectedTab by remember { mutableStateOf("voice") }
    var hasPermission by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    var hasBlePermission by remember { mutableStateOf(hasBlePermissions(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> hasPermission = granted }
    val blePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        hasBlePermission = result.values.all { it } && hasBlePermissions(context)
        if (hasBlePermission) lightController.startScan()
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    val character = characterUi(characterId)
    val characterName = character.name
    val characterSymbol = character.symbol

    LaunchedEffect(Unit) {
        if (address.isNotBlank()) {
            controller.connect(
                address,
                nodeName,
                username,
                requestedVoiceId = voiceId,
                requestedCharacterId = characterId,
            )
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                controller.onPause()
                lightController.stopScan()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            controller.disconnect()
            lightController.close()
            wifiLightController.close()
        }
    }

    Surface(color = Parchment, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TabChoice("Röst", selectedTab == "voice", Modifier.weight(1f)) { selectedTab = "voice" }
                TabChoice("Ljus", selectedTab == "lights", Modifier.weight(1f)) { selectedTab = "lights" }
            }
            if (selectedTab == "voice") {
            Box(Modifier.size(92.dp).background(Forest, CircleShape), contentAlignment = Alignment.Center) {
                Text(characterSymbol, style = MaterialTheme.typography.displayMedium, color = Parchment)
            }
            Text(characterName, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Forest)
            Text(
                "Röst: " + when (voiceId) {
                    "piper-lisa" -> "Lisa"
                    "chatterbox" -> "Chatterbox"
                    "moss-nano" -> "MOSS Nano"
                    "moss-christian" -> "Christian"
                    "matcha-sherlock" -> "GrapheneOS Matcha English"
                    else -> "NST"
                },
                style = MaterialTheme.typography.bodySmall,
                color = Forest,
            )
            Text(state.connectionLabel, color = if (state.canTalk) Forest else Copper)
            Text(state.serverAddress.ifBlank { "Ingen server vald" }, style = MaterialTheme.typography.bodySmall)

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { controller.connect(address, nodeName, username, requestedVoiceId = voiceId, requestedCharacterId = characterId) }, enabled = address.isNotBlank()) { Text("Anslut") }
                OutlinedButton(onClick = {
                    settingsAddress = address
                    settingsNodeName = nodeName
                    settingsUsername = username
                    settingsCharacterId = characterId
                    settingsVoiceId = voiceId
                    settingsPassword = ""
                    showSettings = true
                }) { Text("Inställningar") }
            }

            if (!hasPermission) {
                Button(onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }) { Text("Tillåt mikrofon") }
            }

            PushToTalkButton(
                active = state.microphoneActive,
                enabled = state.canTalk && hasPermission && !state.conversationActive,
                onStart = controller::startTalking,
                onStop = controller::stopTalking,
            )
            if (state.conversationActive) {
                Button(onClick = controller::stopConversation, colors = ButtonDefaults.buttonColors(containerColor = Copper)) {
                    Text("Avsluta samtal")
                }
                if (state.status == VoiceStatus.Speaking || state.status == VoiceStatus.Processing) {
                    OutlinedButton(onClick = controller::interruptAndListen) {
                        Text("Avbryt och tala")
                    }
                }
                Text(
                    when {
                        state.interruptionListening -> "Samtalsläge: du kan avbryta $characterName genom att tala"
                        state.microphoneActive -> "Samtalsläge: lyssnar efter din röst"
                        else -> "Samtalsläge: $characterName svarar"
                    },
                    color = Forest,
                    textAlign = TextAlign.Center,
                )
            } else {
                Button(
                    onClick = controller::startConversation,
                    enabled = state.canTalk && hasPermission,
                ) { Text("Starta samtal") }
            }
            Text(state.status.name, style = MaterialTheme.typography.titleMedium, color = if (state.status == VoiceStatus.Error) Color.Red else Forest)
            if (state.errorMessage != null) Text(state.errorMessage!!, color = Color.Red, textAlign = TextAlign.Center)
            if (state.droppedCaptureFrames > 0) Text("Tappade mikrofonblock: ${state.droppedCaptureFrames}", color = Color.Red)

            TranscriptCard("Preliminärt", state.partialTranscript)
            TranscriptCard("Du sade", state.finalTranscript)
            TranscriptCard(characterName, state.responseText)
            if (state.actionMessage != null) {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF1C7))) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Åtgärd", fontWeight = FontWeight.Bold, color = Forest)
                        Text(state.actionMessage!!)
                        if (state.pendingAction != null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(onClick = controller::confirmPendingAction) { Text("Skapa privat lista") }
                                OutlinedButton(onClick = controller::rejectPendingAction) { Text("Avbryt") }
                            }
                        }
                    }
                }
            }
            LatencyCard(state.latencies)

            OutlinedButton(onClick = controller::cancelResponse, enabled = state.status == VoiceStatus.Speaking || state.status == VoiceStatus.Processing) {
                Text("Avbryt uppspelning")
            }
            } else {
                LightPanel(
                    wifiState = wifiLightState,
                    bleState = lightState,
                    hasPermission = hasBlePermission,
                    onWifiDiscover = wifiLightController::discover,
                    onWifiRefresh = wifiLightController::refresh,
                    onWifiPower = wifiLightController::setPower,
                    onWifiColor = wifiLightController::setColor,
                    onWifiPreciseColor = wifiLightController::setColorBrightness,
                    onWifiEffect = wifiLightController::setEffect,
                    onWifiProvision = wifiLightController::provision,
                    configuredLights = state.configuredLights,
                    configMessage = state.lightConfigMessage,
                    onSaveConfig = controller::saveLightConfig,
                    onRequestPermission = { blePermissionLauncher.launch(requiredBlePermissions()) },
                    onStartScan = lightController::startScan,
                    onStopScan = lightController::stopScan,
                    onInspect = lightController::inspect,
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    if (showSettings) AlertDialog(
        onDismissRequest = { showSettings = false },
        title = { Text("Anslutning") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(settingsAddress, { settingsAddress = it }, label = { Text("Serveradress") }, placeholder = { Text("wss://apothictech.se/euthervox/ws") }, singleLine = true)
                OutlinedTextField(settingsNodeName, { settingsNodeName = it }, label = { Text("Nodnamn") }, singleLine = true)
                OutlinedTextField(settingsUsername, { settingsUsername = it }, label = { Text("EutherOxide-användare") }, singleLine = true)
                Text("Figur", fontWeight = FontWeight.Bold, color = Forest)
                CharacterChoice("skinnskattaren", "Skinnskattaren", settingsCharacterId) {
                    settingsCharacterId = it
                    if (settingsVoiceId == "moss-christian" || settingsVoiceId == "matcha-sherlock") settingsVoiceId = "piper-nst"
                }
                CharacterChoice("christian-grosshandlare", "Christian – ilsken dansk grosshandlare", settingsCharacterId) {
                    settingsCharacterId = it
                    settingsVoiceId = "moss-christian"
                }
                CharacterChoice("sherlock-holmes", "Sherlock Holmes – answers in English", settingsCharacterId) {
                    settingsCharacterId = it
                    settingsVoiceId = "matcha-sherlock"
                }
                Text("Röst", fontWeight = FontWeight.Bold, color = Forest)
                if (settingsCharacterId == "christian-grosshandlare") {
                    VoiceChoice("moss-christian", "Christian – dansk MOSS-röst", settingsVoiceId) { settingsVoiceId = it }
                } else if (settingsCharacterId == "sherlock-holmes") {
                    VoiceChoice("matcha-sherlock", "GrapheneOS Matcha – English", settingsVoiceId) { settingsVoiceId = it }
                } else {
                    VoiceChoice("piper-nst", "NST – snabb (rekommenderad)", settingsVoiceId) { settingsVoiceId = it }
                    VoiceChoice("piper-lisa", "Lisa – alternativ", settingsVoiceId) { settingsVoiceId = it }
                    VoiceChoice("moss-nano", "MOSS Nano – Sören Svartkrut", settingsVoiceId) { settingsVoiceId = it }
                    VoiceChoice("chatterbox", "Chatterbox – långsam (experimentell)", settingsVoiceId) { settingsVoiceId = it }
                }
                OutlinedTextField(
                    settingsPassword,
                    { settingsPassword = it },
                    label = { Text("Lösenord (endast första gången)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                Text("Vid wss:// växlas lösenordet mot en app-token som krypteras med Android Keystore. Lösenordet sparas aldrig. ws:// ska endast användas på betrott LAN.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(EutherAuthClient.youtubeOAuthUrl(settingsAddress))))
                        }
                    },
                    enabled = settingsAddress.isNotBlank(),
                ) { Text("Koppla YouTube-konto") }
                OutlinedButton(
                    onClick = {
                        context.packageManager.getLaunchIntentForPackage("com.google.android.apps.youtube.music")?.let(context::startActivity)
                    },
                ) { Text("Öppna YouTube Music / Cast") }
                Text("Manuell reservväg: välj Cast-symbolen i YouTube Music och anslut till Kök 2.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { settingsPassword = ""; controller.forgetCredentials() }) { Text("Glöm sparad inloggning") }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val savedAddress = settingsAddress.trim()
                val savedNodeName = settingsNodeName.trim()
                val savedUsername = settingsUsername.trim()
                val savedCharacterId = settingsCharacterId
                val savedVoiceId = settingsVoiceId
                preferences.edit(commit = true) {
                    putString("server_address", savedAddress)
                    putString("node_name", savedNodeName)
                    putString("username", savedUsername)
                    putString("character_id", savedCharacterId)
                    putString("voice_id", savedVoiceId)
                }
                address = savedAddress
                nodeName = savedNodeName
                username = savedUsername
                characterId = savedCharacterId
                voiceId = savedVoiceId
                showSettings = false
                controller.connect(savedAddress, savedNodeName, savedUsername, settingsPassword, savedVoiceId, savedCharacterId)
                settingsPassword = ""
            }, enabled = settingsAddress.isNotBlank()) { Text("Spara och stäng") }
        },
        dismissButton = {
            TextButton(onClick = { showSettings = false }) { Text("Avbryt") }
        },
    )
}

@Composable
private fun TabChoice(label: String, selected: Boolean, modifier: Modifier = Modifier, onSelect: () -> Unit) {
    if (selected) {
        Button(onClick = onSelect, modifier = modifier) { Text(label) }
    } else {
        OutlinedButton(onClick = onSelect, modifier = modifier) { Text(label) }
    }
}

@Composable
private fun LightPanel(
    wifiState: MagicHomeWifiUiState,
    bleState: se.euther.euthervox.lights.BleLightUiState,
    hasPermission: Boolean,
    onWifiDiscover: () -> Unit,
    onWifiRefresh: (MagicHomeDevice) -> Unit,
    onWifiPower: (MagicHomeDevice, Boolean) -> Unit,
    onWifiColor: (MagicHomeDevice, Int, Int, Int) -> Unit,
    onWifiPreciseColor: (MagicHomeDevice, Int, Int, Int, Int) -> Unit,
    onWifiEffect: (MagicHomeDevice, String, Int) -> Unit,
    onWifiProvision: (String, String, () -> Unit) -> Unit,
    configuredLights: List<ServerEvent.ConfiguredLight>,
    configMessage: String?,
    onSaveConfig: (MagicHomeDevice, String, String) -> Unit,
    onRequestPermission: () -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onInspect: (BleLightDevice) -> Unit,
) {
    val context = LocalContext.current
    var advancedBle by remember { mutableStateOf(false) }
    var showProvisioning by remember { mutableStateOf(false) }
    var provisioningSsid by remember { mutableStateOf("") }
    var provisioningPassword by remember { mutableStateOf("") }
    var editingDevice by remember { mutableStateOf<MagicHomeDevice?>(null) }
    var editingName by remember { mutableStateOf("") }
    var editingRoom by remember { mutableStateOf("") }

    Text("Magic Home-ljus", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Forest)
    Text(
        "Styr Wi-Fi-slingor lokalt och lägg till nya moduler utan molnkonto.",
        textAlign = TextAlign.Center,
        color = Forest,
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = onWifiDiscover, enabled = !wifiState.scanning, modifier = Modifier.weight(1f)) {
            Text(if (wifiState.scanning) "Söker…" else "Sök Wi-Fi")
        }
        OutlinedButton(onClick = { showProvisioning = true }, modifier = Modifier.weight(1f)) {
            Text("Lägg till ny")
        }
    }
    Text(wifiState.status, textAlign = TextAlign.Center)
    wifiState.error?.let { Text(it, color = Color.Red, textAlign = TextAlign.Center) }
    configMessage?.let { Text(it, color = Forest, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center) }

    wifiState.devices.forEach { device ->
        val configured = configuredLights.firstOrNull { it.mac.filter(Char::isLetterOrDigit).equals(device.mac.filter(Char::isLetterOrDigit), true) }
        WifiDeviceCard(
            device = device,
            configured = configured,
            busy = wifiState.busyIp == device.ip,
            onRefresh = { onWifiRefresh(device) },
            onPower = { on -> onWifiPower(device, on) },
            onColor = { red, green, blue -> onWifiColor(device, red, green, blue) },
            onPreciseColor = { red, green, blue, brightness -> onWifiPreciseColor(device, red, green, blue, brightness) },
            onEffect = { effect, speed -> onWifiEffect(device, effect, speed) },
            onEdit = {
                editingDevice = device
                editingName = configured?.name.orEmpty()
                editingRoom = configured?.room.orEmpty()
            },
        )
    }

    OutlinedButton(onClick = { advancedBle = !advancedBle }) {
        Text(if (advancedBle) "Dölj BLE-reserv" else "Visa BLE-reserv")
    }
    if (advancedBle) {
        Text("BLE-ljuslaboratorium", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Forest)
        Text(
            "För andra typer av slingor kan BLE-fingeravtrycket fortfarande undersökas.",
            textAlign = TextAlign.Center,
            color = Forest,
        )
        if (!hasPermission) {
            Button(onClick = onRequestPermission) { Text("Tillåt enheter i närheten") }
        } else {
            Button(onClick = if (bleState.scanning) onStopScan else onStartScan) {
                Text(if (bleState.scanning) "Stoppa BLE-sökning" else "Sök BLE-slingor")
            }
        }
        Text(bleState.status, textAlign = TextAlign.Center)
        bleState.error?.let { Text(it, color = Color.Red, textAlign = TextAlign.Center) }

        bleState.devices.forEach { device ->
            BleDeviceCard(device, bleState.inspectingAddress == device.address) { onInspect(device) }
        }

        val inspected = bleState.inspectedDevice
        if (inspected != null) {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFE4DDCB))) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("GATT-diagnostik", fontWeight = FontWeight.Bold, color = Forest)
                    Text("${inspected.name} · ${inspected.protocol.label}")
                    if (bleState.characteristics.isEmpty()) {
                        Text("Inga karakteristiker lästa ännu.")
                    } else {
                        bleState.characteristics.forEach {
                            Text("${it.serviceUuid} → ${it.characteristicUuid} · ${it.properties}", style = MaterialTheme.typography.bodySmall)
                        }
                        Button(onClick = {
                            val clipboard = context.getSystemService(ClipboardManager::class.java)
                            clipboard.setPrimaryClip(ClipData.newPlainText("EutherVox BLE diagnostic", bleState.diagnosticText()))
                        }) { Text("Kopiera diagnostik") }
                    }
                }
            }
        }
    }

    if (showProvisioning) AlertDialog(
        onDismissRequest = {
            provisioningPassword = ""
            showProvisioning = false
        },
        title = { Text("Lägg till Wi-Fi-modul") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("1. Återställ modulen tills den skapar ett nät som börjar med LEDnet.")
                OutlinedButton(onClick = {
                    val intent = Intent(Settings.Panel.ACTION_WIFI)
                    runCatching { context.startActivity(intent) }.onFailure {
                        context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                    }
                }) { Text("Öppna Wi-Fi och anslut till LEDnet") }
                Text("2. Gå tillbaka hit och ange ditt vanliga 2,4 GHz-nät.")
                OutlinedTextField(
                    value = provisioningSsid,
                    onValueChange = { provisioningSsid = it },
                    label = { Text("Nätverksnamn (SSID)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = provisioningPassword,
                    onValueChange = { provisioningPassword = it },
                    label = { Text("Wi-Fi-lösenord") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                Text(
                    "Uppgifterna skickas direkt från telefonen till modulen och sparas inte av EutherVox.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onWifiProvision(provisioningSsid, provisioningPassword) {
                        provisioningPassword = ""
                        showProvisioning = false
                    }
                },
                enabled = !wifiState.provisioning,
            ) { Text(if (wifiState.provisioning) "Skickar…" else "Konfigurera modul") }
        },
        dismissButton = {
            TextButton(onClick = {
                provisioningPassword = ""
                showProvisioning = false
            }) { Text("Avbryt") }
        },
    )

    editingDevice?.let { device ->
        AlertDialog(
            onDismissRequest = { editingDevice = null },
            title = { Text("Namnge ljuset") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("${device.model} · ${device.mac}", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = editingName,
                        onValueChange = { editingName = it },
                        label = { Text("Lampnamn, t.ex. Fönstret") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = editingRoom,
                        onValueChange = { editingRoom = it },
                        label = { Text("Rum, t.ex. köket") },
                        singleLine = true,
                    )
                    Text("Namnet sparas i serverns lights.toml och blir tillgängligt för röststyrning.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onSaveConfig(device, editingName, editingRoom)
                        editingDevice = null
                    },
                    enabled = editingName.isNotBlank() && editingRoom.isNotBlank(),
                ) { Text("Spara namn") }
            },
            dismissButton = { TextButton(onClick = { editingDevice = null }) { Text("Avbryt") } },
        )
    }
}

@Composable
private fun WifiDeviceCard(
    device: MagicHomeDevice,
    configured: ServerEvent.ConfiguredLight?,
    busy: Boolean,
    onRefresh: () -> Unit,
    onPower: (Boolean) -> Unit,
    onColor: (Int, Int, Int) -> Unit,
    onPreciseColor: (Int, Int, Int, Int) -> Unit,
    onEffect: (String, Int) -> Unit,
    onEdit: () -> Unit,
) {
    var showPicker by remember(device.mac) { mutableStateOf(false) }
    var showEffects by remember(device.mac) { mutableStateOf(false) }
    var effectSpeed by remember(device.mac) { mutableStateOf(40f) }
    var activeEffect by remember(device.mac) { mutableStateOf<String?>(null) }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.76f))) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(configured?.name ?: device.model, fontWeight = FontWeight.Bold, color = Forest)
                    configured?.let { Text(it.room, style = MaterialTheme.typography.bodySmall, color = Forest) }
                }
                TextButton(onClick = onEdit) { Text(if (configured == null) "Namnge" else "Byt namn") }
            }
            Text("${device.ip} · ${device.mac}", style = MaterialTheme.typography.bodySmall)
            Text(
                when (device.powerOn) {
                    true -> "Tänd · ${device.colorHex ?: "färg okänd"}"
                    false -> "Släckt · senast ${device.colorHex ?: "färg okänd"}"
                    null -> "Status ej läst"
                },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Button(onClick = { onPower(true) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Tänd") }
                OutlinedButton(onClick = { onPower(false) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Släck") }
                TextButton(onClick = onRefresh, enabled = !busy) { Text(if (busy) "…" else "Status") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ColorPreset("Röd", Color(0xFFD43A35), Modifier.weight(1f), !busy) { onColor(255, 0, 0) }
                ColorPreset("Grön", Color(0xFF25834A), Modifier.weight(1f), !busy) { onColor(0, 255, 0) }
                ColorPreset("Blå", Color(0xFF315DCC), Modifier.weight(1f), !busy) { onColor(0, 0, 255) }
                ColorPreset("Vit", Color(0xFF777777), Modifier.weight(1f), !busy) { onColor(255, 255, 255) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                OutlinedButton(onClick = { showPicker = !showPicker }, modifier = Modifier.weight(1f)) {
                    Text(if (showPicker) "Dölj färg" else "Exakt färg")
                }
                OutlinedButton(onClick = { showEffects = true }, modifier = Modifier.weight(1f)) { Text("Mönster") }
            }
            if (showPicker) {
                MagicalColorRectangle(enabled = !busy, onCommit = onPreciseColor)
            }
        }
    }
    if (showEffects) AlertDialog(
        onDismissRequest = { showEffects = false },
        title = { Text("Blink och färgskiften") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("Hastighet ${effectSpeed.toInt()} %")
                Slider(
                    value = effectSpeed,
                    onValueChange = { effectSpeed = it },
                    onValueChangeFinished = {
                        activeEffect?.let { onEffect(it, effectSpeed.toInt()) }
                    },
                    valueRange = 1f..100f,
                    enabled = !busy,
                )
                Text(
                    activeEffect?.let { "Aktivt: $it. Ny hastighet skickas när reglaget släpps." }
                        ?: "Välj först ett mönster. Därefter ändrar reglaget hastigheten direkt.",
                    style = MaterialTheme.typography.bodySmall,
                )
                MagicHomeProtocol.effects.keys.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        row.forEach { label ->
                            OutlinedButton(
                                onClick = {
                                    activeEffect = label
                                    onEffect(label, effectSpeed.toInt())
                                },
                                enabled = !busy,
                                modifier = Modifier.weight(1f),
                            ) { Text(label, style = MaterialTheme.typography.bodySmall) }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { showEffects = false }) { Text("Stäng") } },
    )
}

@Composable
private fun MagicalColorRectangle(enabled: Boolean, onCommit: (Int, Int, Int, Int) -> Unit) {
    var hue by remember { mutableStateOf(285f) }
    var saturation by remember { mutableStateOf(0.82f) }
    var brightness by remember { mutableStateOf(80f) }
    fun rgb(): Triple<Int, Int, Int> {
        val color = Color.hsv(hue, saturation, 1f)
        return Triple((color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt())
    }
    fun commit() {
        val (red, green, blue) = rgb()
        onCommit(red, green, blue, brightness.toInt())
    }
    val (red, green, blue) = rgb()
    val selected = Color(red, green, blue)
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth().height(126.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Canvas(
                Modifier.weight(1f).fillMaxSize().pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    fun update(position: Offset) {
                        hue = (position.x / size.width).coerceIn(0f, 1f) * 360f
                        saturation = 1f - (position.y / size.height).coerceIn(0f, 1f)
                    }
                    detectDragGestures(
                        onDragStart = { update(it) },
                        onDragEnd = ::commit,
                    ) { change, _ -> change.consume(); update(change.position) }
                },
            ) {
                val columns = 48
                val rows = 16
                val cellWidth = size.width / columns
                val cellHeight = size.height / rows
                repeat(columns) { x ->
                    repeat(rows) { y ->
                        drawRect(
                            color = Color.hsv(x * 360f / columns, 1f - y.toFloat() / (rows - 1), 1f),
                            topLeft = Offset(x * cellWidth, y * cellHeight),
                            size = Size(cellWidth + 1f, cellHeight + 1f),
                        )
                    }
                }
                val marker = Offset(hue / 360f * size.width, (1f - saturation) * size.height)
                drawCircle(Color.White, 9.dp.toPx(), marker)
                drawCircle(Color.Black, 6.dp.toPx(), marker)
                drawCircle(selected, 4.dp.toPx(), marker)
            }
            Canvas(
                Modifier.size(width = 34.dp, height = 126.dp).pointerInput(enabled, selected) {
                    if (!enabled) return@pointerInput
                    fun update(position: Offset) { brightness = (100f - position.y / size.height * 100f).coerceIn(1f, 100f) }
                    detectDragGestures(onDragStart = { update(it) }, onDragEnd = ::commit) { change, _ ->
                        change.consume(); update(change.position)
                    }
                },
            ) {
                drawRect(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(selected, Color.Black)))
                val y = (1f - brightness / 100f) * size.height
                drawLine(Color.White, Offset(0f, y), Offset(size.width, y), strokeWidth = 4.dp.toPx())
                drawLine(Color.Black, Offset(0f, y + 3.dp.toPx()), Offset(size.width, y + 3.dp.toPx()), strokeWidth = 2.dp.toPx())
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("#%02X%02X%02X".format(red, green, blue), style = MaterialTheme.typography.bodySmall)
            Text("Ljusstyrka ${brightness.toInt()} %", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ColorPreset(label: String, color: Color, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 3.dp, vertical = 8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color),
    ) { Text(label, style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun BleDeviceCard(device: BleLightDevice, inspecting: Boolean, onInspect: () -> Unit) {
    val likely = device.protocol != BleLightProtocol.Unknown
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = if (likely) Color(0xFFFFF1C7) else Color.White.copy(alpha = 0.72f)),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(device.name, fontWeight = FontWeight.Bold, color = Forest)
            Text("${device.protocol.label} · RSSI ${device.rssi} dBm", style = MaterialTheme.typography.bodySmall)
            Text(device.address, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onInspect, enabled = !inspecting) {
                Text(if (inspecting) "Undersöker…" else "Undersök")
            }
        }
    }
}

@Composable
private fun VoiceChoice(id: String, label: String, selected: String, onSelect: (String) -> Unit) {
    if (selected == id) {
        Button(onClick = { onSelect(id) }, modifier = Modifier.fillMaxWidth()) { Text(label) }
    } else {
        OutlinedButton(onClick = { onSelect(id) }, modifier = Modifier.fillMaxWidth()) { Text(label) }
    }
}

@Composable
private fun CharacterChoice(id: String, label: String, selected: String, onSelect: (String) -> Unit) {
    if (selected == id) {
        Button(onClick = { onSelect(id) }, modifier = Modifier.fillMaxWidth()) { Text(label) }
    } else {
        OutlinedButton(onClick = { onSelect(id) }, modifier = Modifier.fillMaxWidth()) { Text(label) }
    }
}

@Composable
private fun PushToTalkButton(active: Boolean, enabled: Boolean, onStart: () -> Unit, onStop: (Boolean) -> Unit) {
    Box(
        modifier = Modifier.size(190.dp).background(if (active) Copper else if (enabled) Forest else Color.Gray, CircleShape)
            .pointerInput(enabled) {
                if (enabled) detectTapGestures(onPress = {
                    onStart()
                    val releasedNormally = tryAwaitRelease()
                    onStop(!releasedNormally)
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(if (active) "LYSSNAR\nsläpp för att skicka" else "HÅLL INNE\nOCH TALA", color = Color.White, textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TranscriptCard(label: String, value: String) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.72f)), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(label, fontWeight = FontWeight.Bold, color = Forest)
            Text(value.ifBlank { "—" })
        }
    }
}

@Composable
private fun LatencyCard(latencies: Latencies) {
    fun value(ms: Long?) = ms?.let { "$it ms" } ?: "—"
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFE4DDCB))) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Latens", fontWeight = FontWeight.Bold, color = Forest)
            listOf(
                "Capture start" to latencies.captureStartMs,
                "Upload" to latencies.uploadMs,
                "STT final" to latencies.sttFinalMs,
                "First response text" to latencies.firstResponseTextMs,
                "First TTS audio" to latencies.firstTtsAudioMs,
                "Audio playback start" to latencies.audioPlaybackStartMs,
                "Total response latency" to latencies.totalResponseLatencyMs,
            ).forEach { (label, ms) -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("$label:"); Text(value(ms)) } }
        }
    }
}
