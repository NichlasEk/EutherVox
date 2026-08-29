package se.euther.euthervox

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.animation.Crossfade
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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
import se.euther.euthervox.app.VoiceStatus
import se.euther.euthervox.background.EutherVoxNodeRuntime
import se.euther.euthervox.background.EutherVoxNodeService
import se.euther.euthervox.network.EutherAuthClient
import se.euther.euthervox.lights.BleLightController
import se.euther.euthervox.lights.BleLightDevice
import se.euther.euthervox.lights.BleLightProtocol
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import se.euther.euthervox.lights.hasBlePermissions
import se.euther.euthervox.lights.requiredBlePermissions
import se.euther.euthervox.lights.MagicHomeDevice
import se.euther.euthervox.lights.MagicHomeWifiController
import se.euther.euthervox.lights.MagicHomeWifiUiState
import se.euther.euthervox.lights.MagicHomeProtocol
import se.euther.euthervox.protocol.ServerEvent
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { EutherVoxApp() } }
    }
}

private val Forest = Color(0xFF254C3A)
private val Copper = Color(0xFFB86035)
private val Parchment = Color(0xFFF2EBDD)

private data class DeviceDestination(
    val tab: String,
    val label: String,
    val symbol: String,
    val hero: Int,
)

private val DeviceDestinations = listOf(
    DeviceDestination("voice", "Röst", "◉", R.drawable.hero_voice),
    DeviceDestination("lights", "Ljus", "✦", R.drawable.hero_lights),
    DeviceDestination("tv", "TV", "▣", R.drawable.hero_tv),
    DeviceDestination("washer", "Tvättmaskin", "◎", R.drawable.hero_washer),
    DeviceDestination("dryer", "Torktumlare", "◌", R.drawable.hero_dryer),
    DeviceDestination("boiler", "Panna", "♨", R.drawable.hero_boiler),
    DeviceDestination("pump", "Pump", "≈", R.drawable.hero_pump),
    DeviceDestination("vacuum", "Dammsugare", "◒", R.drawable.hero_vacuum),
)

private data class CharacterUi(val name: String, val symbol: String)

private fun characterUi(id: String) = when (id) {
    "christian-grosshandlare" -> CharacterUi("Christian Grosshandlare", "⚓")
    "sherlock-holmes" -> CharacterUi("Sherlock Holmes", "⌕")
    else -> CharacterUi("Skinnskattaren", "⛏")
}

private fun llmModelLabel(model: String) = when (model) {
    "qwen3:4b-instruct" -> "Qwen3 4B – snabb"
    "qwen3.8:27b" -> "Qwen3.8 27B – smartare"
    else -> model
}

@Composable
fun EutherVoxApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember { EutherVoxNodeRuntime.controller(context) }
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
    var llmModel by remember { mutableStateOf(preferences.getString("llm_model", "qwen3:4b-instruct").orEmpty()) }
    var backgroundNodeEnabled by remember {
        mutableStateOf(preferences.getBoolean(EutherVoxNodeService.PREFERENCE_ENABLED, false))
    }
    var automaticBargeInEnabled by remember {
        mutableStateOf(preferences.getBoolean("automatic_barge_in", false))
    }
    var settingsAddress by remember { mutableStateOf(address) }
    var settingsNodeName by remember { mutableStateOf(nodeName) }
    var settingsUsername by remember { mutableStateOf(username) }
    var settingsCharacterId by remember { mutableStateOf(characterId) }
    var settingsVoiceId by remember { mutableStateOf(voiceId) }
    var settingsLlmModel by remember { mutableStateOf(llmModel) }
    var settingsBackgroundNodeEnabled by remember { mutableStateOf(backgroundNodeEnabled) }
    var settingsAutomaticBargeInEnabled by remember { mutableStateOf(automaticBargeInEnabled) }
    var settingsPassword by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(address.isBlank()) }
    var selectedTab by remember { mutableStateOf("voice") }
    var hasPermission by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    var hasBlePermission by remember { mutableStateOf(hasBlePermissions(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> hasPermission = granted }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    val blePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        hasBlePermission = result.values.all { it } && hasBlePermissions(context)
        if (hasBlePermission) lightController.startScan()
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    val character = characterUi(characterId)
    val characterName = character.name
    val characterSymbol = character.symbol

    LaunchedEffect(automaticBargeInEnabled) {
        controller.setAutomaticBargeInEnabled(automaticBargeInEnabled)
    }

    LaunchedEffect(Unit) {
        if (address.isNotBlank()) {
            if (backgroundNodeEnabled) {
                EutherVoxNodeService.start(context)
            } else {
                controller.connect(
                    address,
                    nodeName,
                    username,
                    requestedVoiceId = voiceId,
                    requestedCharacterId = characterId,
                    requestedLlmModel = llmModel,
                )
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                controller.onPause()
                lightController.stopScan()
                wifiLightController.stopMusicMode()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (!preferences.getBoolean(EutherVoxNodeService.PREFERENCE_ENABLED, false)) {
                controller.disconnect()
            }
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
            DeviceNavigator(
                selectedTab = selectedTab,
                onlineByTab = mapOf(
                    "voice" to state.canTalk,
                    "lights" to (wifiLightState.devices.isNotEmpty() || lightState.devices.isNotEmpty()),
                    "tv" to state.configuredTvs.isNotEmpty(),
                    "washer" to state.washerState?.online,
                    "dryer" to null,
                    "boiler" to null,
                    "pump" to state.pumpState?.online,
                    "vacuum" to state.vacuumState?.online,
                ),
                onSelect = { selectedTab = it },
            )
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
            if (backgroundNodeEnabled) {
                Text("Bakgrundsnod aktiv", style = MaterialTheme.typography.bodySmall, color = Forest)
            }
            Text(state.serverAddress.ifBlank { "Ingen server vald" }, style = MaterialTheme.typography.bodySmall)

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { controller.connect(address, nodeName, username, requestedVoiceId = voiceId, requestedCharacterId = characterId, requestedLlmModel = llmModel) }, enabled = address.isNotBlank()) { Text("Anslut") }
                OutlinedButton(onClick = {
                    settingsAddress = address
                    settingsNodeName = nodeName
                    settingsUsername = username
                    settingsCharacterId = characterId
                    settingsVoiceId = voiceId
                    settingsLlmModel = llmModel
                    settingsBackgroundNodeEnabled = backgroundNodeEnabled
                    settingsAutomaticBargeInEnabled = automaticBargeInEnabled
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
                onStart = {
                    wifiLightController.stopMusicMode()
                    controller.startTalking()
                },
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
                    onClick = {
                        wifiLightController.stopMusicMode()
                        controller.startConversation()
                    },
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

            OutlinedButton(
                onClick = {
                    val diagnostic = buildString {
                        appendLine("EutherVox ${BuildConfig.VERSION_NAME} röstdiagnostik")
                        appendLine("status=${state.status} connection=${state.connectionLabel}")
                        appendLine("conversation=${state.conversationActive} microphone=${state.microphoneActive}")
                        appendLine("automatic_barge_in=${state.automaticBargeInEnabled}")
                        append(state.voiceDiagnostics.joinToString("\n"))
                    }
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                        ClipData.newPlainText("EutherVox röstdiagnostik", diagnostic),
                    )
                },
                enabled = state.voiceDiagnostics.isNotEmpty(),
            ) { Text("Kopiera röstdiagnostik") }

            OutlinedButton(onClick = controller::cancelResponse, enabled = state.status == VoiceStatus.Speaking || state.status == VoiceStatus.Processing) {
                Text("Avbryt uppspelning")
            }
            } else if (selectedTab == "lights") {
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
                    onStartMusic = { devices, red, green, blue, sensitivity ->
                        controller.onPause()
                        wifiLightController.startMusicMode(devices, red, green, blue, sensitivity)
                    },
                    onStopMusic = wifiLightController::stopMusicMode,
                    onWifiProvision = wifiLightController::provision,
                    hasMicrophonePermission = hasPermission,
                    onRequestMicrophonePermission = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                    configuredLights = state.configuredLights,
                    configMessage = state.lightConfigMessage,
                    onSaveConfig = controller::saveLightConfig,
                    onRequestPermission = { blePermissionLauncher.launch(requiredBlePermissions()) },
                    onStartScan = lightController::startScan,
                    onStopScan = lightController::stopScan,
                    onInspect = lightController::inspect,
                )
            } else if (selectedTab == "tv") {
                TvPanel(
                    configured = state.configuredTvs,
                    discovered = state.discoveredTvs,
                    message = state.tvMessage,
                    busy = state.tvBusy,
                    onDiscover = controller::discoverTvs,
                    onSave = controller::saveTv,
                    onCommand = controller::controlTv,
                )
            } else if (selectedTab == "pump") {
                PumpPanel(
                    configured = state.configuredPumps,
                    state = state.pumpState,
                    message = state.pumpMessage,
                    busy = state.pumpBusy,
                    onRefresh = controller::refreshPump,
                    onControl = controller::controlPump,
                )
            } else if (selectedTab == "washer") {
                WasherPanel(
                    state = state.washerState,
                    statistics = state.washerStatistics,
                    programs = state.washerPrograms,
                    waterTemperatures = state.washerWaterTemperatures,
                    schedule = state.washerSchedule,
                    message = state.washerMessage,
                    busy = state.washerBusy,
                    controlsAvailable = state.washerControlsAvailable,
                    onRefresh = controller::refreshWasher,
                    onCommand = controller::controlWasher,
                    onSchedule = controller::createWasherSchedule,
                    onCancelSchedule = controller::cancelWasherSchedule,
                )
            } else if (selectedTab == "vacuum") {
                VacuumPanel(
                    vacuum = state.vacuumState,
                    maps = state.vacuumMaps,
                    message = state.vacuumMessage,
                    mapMessage = state.vacuumMapsMessage,
                    busy = state.vacuumBusy,
                    mapsBusy = state.vacuumMapsBusy,
                    controlsAvailable = state.vacuumControlsAvailable,
                    onRefresh = controller::refreshVacuum,
                    onRefreshMaps = controller::refreshVacuumMaps,
                    onCommand = controller::controlVacuum,
                )
            } else {
                ComingSoonPanel(
                    title = if (selectedTab == "dryer") "Torktumlare" else "Panna",
                    description = if (selectedTab == "dryer") {
                        "Ingen torktumlare är ansluten ännu. Kortet är redo för lokal status och styrning när vi kopplar in den."
                    } else {
                        "Ingen separat panna är ansluten ännu. Kortet är redo för temperaturer, driftstatus och rapporter."
                    },
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
                Text("Språkmodell", fontWeight = FontWeight.Bold, color = Forest)
                val selectableModels = (state.availableLlmModels + listOf(
                    "qwen3:4b-instruct",
                    settingsLlmModel,
                )).filter { it.isNotBlank() }.distinct()
                selectableModels.forEach { model ->
                    VoiceChoice(model, llmModelLabel(model), settingsLlmModel) { settingsLlmModel = it }
                }
                Text(
                    "Qwen3 4B svarar snabbast. Qwen3.8 27B är betydligt större och kan ta längre tid innan första svaret.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Checkbox(
                        checked = settingsBackgroundNodeEnabled,
                        onCheckedChange = { enabled ->
                            settingsBackgroundNodeEnabled = enabled
                            if (
                                enabled &&
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                                PackageManager.PERMISSION_GRANTED
                            ) {
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        },
                    )
                    Column {
                        Text("Håll EutherVox ansluten i bakgrunden")
                        Text(
                            "Tar emot husmeddelanden och TTS utan appfokus. Mikrofonen förblir avstängd.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Checkbox(
                        checked = settingsAutomaticBargeInEnabled,
                        onCheckedChange = { settingsAutomaticBargeInEnabled = it },
                    )
                    Column {
                        Text("Avbryt automatiskt när jag börjar tala")
                        Text(
                            "Experimentellt. Avstängt rekommenderas för att EutherVox inte ska höra sin egen röst.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
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
                val savedLlmModel = settingsLlmModel
                preferences.edit(commit = true) {
                    putString("server_address", savedAddress)
                    putString("node_name", savedNodeName)
                    putString("username", savedUsername)
                    putString("character_id", savedCharacterId)
                    putString("voice_id", savedVoiceId)
                    putString("llm_model", savedLlmModel)
                    putBoolean(EutherVoxNodeService.PREFERENCE_ENABLED, settingsBackgroundNodeEnabled)
                    putBoolean("automatic_barge_in", settingsAutomaticBargeInEnabled)
                }
                address = savedAddress
                nodeName = savedNodeName
                username = savedUsername
                characterId = savedCharacterId
                voiceId = savedVoiceId
                llmModel = savedLlmModel
                backgroundNodeEnabled = settingsBackgroundNodeEnabled
                automaticBargeInEnabled = settingsAutomaticBargeInEnabled
                controller.setAutomaticBargeInEnabled(settingsAutomaticBargeInEnabled)
                showSettings = false
                controller.connect(savedAddress, savedNodeName, savedUsername, settingsPassword, savedVoiceId, savedCharacterId, savedLlmModel)
                if (settingsBackgroundNodeEnabled) {
                    EutherVoxNodeService.start(context)
                } else {
                    EutherVoxNodeService.stopKeepingAlive(context)
                }
                settingsPassword = ""
            }, enabled = settingsAddress.isNotBlank()) { Text("Spara och stäng") }
        },
        dismissButton = {
            TextButton(onClick = { showSettings = false }) { Text("Avbryt") }
        },
    )
}

@Composable
private fun DeviceNavigator(
    selectedTab: String,
    onlineByTab: Map<String, Boolean?>,
    onSelect: (String) -> Unit,
) {
    val selected = DeviceDestinations.firstOrNull { it.tab == selectedTab } ?: DeviceDestinations.first()
    val middle = Int.MAX_VALUE / 2
    val alignedStart = middle - (middle % DeviceDestinations.size) + DeviceDestinations.indexOf(selected)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = alignedStart)
    val snap = rememberSnapFlingBehavior(lazyListState = listState)
    val scope = rememberCoroutineScope()
    val currentSelectedTab by rememberUpdatedState(selectedTab)
    val currentOnSelect by rememberUpdatedState(onSelect)

    LaunchedEffect(listState) {
        snapshotFlow {
            if (listState.isScrollInProgress) return@snapshotFlow null
            val viewportCenter = (listState.layoutInfo.viewportStartOffset + listState.layoutInfo.viewportEndOffset) / 2
            listState.layoutInfo.visibleItemsInfo.minByOrNull { item ->
                abs((item.offset + item.size / 2) - viewportCenter)
            }?.index
        }.collect { index ->
            index ?: return@collect
            val destination = DeviceDestinations[index % DeviceDestinations.size]
            if (destination.tab != currentSelectedTab) currentOnSelect(destination.tab)
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 5.dp),
    ) {
        Box(Modifier.fillMaxSize()) {
            Crossfade(targetState = selected, label = "device-hero") { destination ->
                Image(
                    painter = painterResource(destination.hero),
                    contentDescription = "${destination.label}, miljöbild",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Surface(
                color = Color.Black.copy(alpha = 0.52f),
                shape = RoundedCornerShape(50),
                modifier = Modifier.padding(14.dp).align(Alignment.TopStart),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val online = onlineByTab[selected.tab]
                    Box(Modifier.size(9.dp).background(if (online == true) Color(0xFF55DB71) else Color.LightGray, CircleShape))
                    Text(
                        when (online) { true -> "LIVE"; false -> "OFFLINE"; null -> "EJ ANSLUTEN" },
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            Surface(
                color = Color.Black.copy(alpha = 0.46f),
                shape = RoundedCornerShape(topEnd = 18.dp),
                modifier = Modifier.align(Alignment.BottomStart),
            ) {
                Text(
                    selected.label,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val edgePadding = ((maxWidth - 126.dp) / 2).coerceAtLeast(0.dp)
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            state = listState,
            flingBehavior = snap,
            contentPadding = PaddingValues(horizontal = edgePadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(Int.MAX_VALUE) { index ->
                val destination = DeviceDestinations[index % DeviceDestinations.size]
                val isSelected = destination.tab == selectedTab
                Card(
                    onClick = {
                        currentOnSelect(destination.tab)
                        scope.launch { listState.animateScrollToItem(index) }
                    },
                    modifier = Modifier.width(126.dp).height(82.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) Color(0xFF6942A8) else Color(0xFFF8F3EA),
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = if (isSelected) 5.dp else 1.dp),
                ) {
                    Row(
                        Modifier.fillMaxSize().padding(horizontal = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        Text(destination.symbol, color = if (isSelected) Color.White else Forest, style = MaterialTheme.typography.headlineSmall)
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(destination.label, color = if (isSelected) Color.White else Forest, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                val online = onlineByTab[destination.tab]
                                Box(Modifier.size(7.dp).background(if (online == true) Color(0xFF55DB71) else Color.LightGray, CircleShape))
                                Text(
                                    when (online) { true -> "ONLINE"; false -> "OFFLINE"; null -> "VÄNTAR" },
                                    color = if (isSelected) Color.White.copy(alpha = 0.78f) else Color.Gray,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ComingSoonPanel(title: String, description: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Forest)
        Text(description, color = Forest, textAlign = TextAlign.Center)
        Text("Inte ansluten", color = Copper, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun WasherPanel(
    state: ServerEvent.WasherState?,
    statistics: ServerEvent.WasherStatistics?,
    programs: List<ServerEvent.WasherProgram>,
    waterTemperatures: List<String>,
    schedule: ServerEvent.WasherSchedule?,
    message: String?,
    busy: Boolean,
    controlsAvailable: Boolean,
    onRefresh: () -> Unit,
    onCommand: (String, Boolean) -> Unit,
    onSchedule: (String, String, String?) -> Unit,
    onCancelSchedule: () -> Unit,
) {
    var pendingConfirmation by remember { mutableStateOf<String?>(null) }
    val initialTime = remember { LocalTime.now().plusHours(1).format(DateTimeFormatter.ofPattern("HH:mm")) }
    var scheduleDate by remember { mutableStateOf(LocalDate.now().toString()) }
    var scheduleTime by remember { mutableStateOf(initialTime) }
    var selectedProgram by remember(programs) { mutableStateOf(programs.firstOrNull()) }
    var programMenuOpen by remember { mutableStateOf(false) }
    var selectedTemperature by remember(waterTemperatures, state?.waterTemperatureC) {
        mutableStateOf(
            state?.waterTemperatureC?.toString()?.takeIf(waterTemperatures::contains)
                ?: "40".takeIf(waterTemperatures::contains)
                ?: waterTemperatures.firstOrNull()
        )
    }
    var temperatureMenuOpen by remember { mutableStateOf(false) }
    var confirmSchedule by remember { mutableStateOf(false) }
    fun number(value: Double?, suffix: String) = value?.let { "%.1f%s".format(it, suffix) } ?: "—"
    fun stateLabel(value: String) = when (value) {
        "idle" -> "Redo"
        "running" -> "Tvättar"
        "paused" -> "Pausad"
        "finished" -> "Klar"
        "offline" -> "Offline"
        else -> "Okänd"
    }
    fun temperatureLabel(value: String) = if (value == "Cold") "Kallt" else "$value °C"
    Text("Tvättmaskin", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Forest)
    Text("Status, lokal programväljare och serverstyrd schemaläggning.", textAlign = TextAlign.Center, color = Forest)
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.82f))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(state?.let { stateLabel(it.state) } ?: "Ingen status", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Forest)
                Text(if (state?.online == true) "● ONLINE" else "○ OFFLINE", color = if (state?.online == true) Forest else Copper, fontWeight = FontWeight.Bold)
            }
            Text(state?.program ?: "Program saknas", style = MaterialTheme.typography.titleMedium)
            state?.progressPercent?.let {
                LinearProgressIndicator(progress = { it / 100f }, modifier = Modifier.fillMaxWidth())
                Text("$it %${state.remainingSeconds?.let { seconds -> " · ${seconds / 60} min kvar" } ?: ""}")
            }
            Text(listOfNotNull(
                state?.waterTemperatureC?.let { "$it °C" },
                state?.spinRpm?.let { "$it varv/min" },
                state?.rinseCycles?.let { "$it sköljningar" },
            ).joinToString(" · ").ifBlank { "Programinställningar saknas" })
            Text("Effekt nu: ${number(state?.instantaneousPowerW, " W")} · Total mätare: ${number(state?.cumulativeEnergyKwh, " kWh")}", style = MaterialTheme.typography.bodySmall)
            if (controlsAvailable) {
                val remoteReady = state?.remoteControlEnabled == true
                if (!remoteReady) {
                    Text("Smart Control är av – slå på det vid tvättmaskinen för att låsa upp kontrollerna.", color = Copper, fontWeight = FontWeight.Bold)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { pendingConfirmation = "start" },
                        enabled = !busy && remoteReady && state?.state == "idle",
                        modifier = Modifier.weight(1f),
                    ) { Text("Starta") }
                    OutlinedButton(
                        onClick = { onCommand("pause", false) },
                        enabled = !busy && remoteReady && state?.state == "running",
                        modifier = Modifier.weight(1f),
                    ) { Text("Pausa") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onCommand("resume", false) },
                        enabled = !busy && remoteReady && state?.state == "paused",
                        modifier = Modifier.weight(1f),
                    ) { Text("Fortsätt") }
                    OutlinedButton(
                        onClick = { pendingConfirmation = "stop" },
                        enabled = !busy && remoteReady && state?.state in setOf("running", "paused"),
                        modifier = Modifier.weight(1f),
                    ) { Text("Stoppa") }
                }
            }
            Button(onClick = onRefresh, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Uppdaterar…" else "Uppdatera") }
            message?.let { Text(it, color = Forest, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
        }
    }
    if (controlsAvailable) {
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF1C7))) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("Schemalägg tvätt", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Forest)
                if (schedule?.state == "scheduled") {
                    Text(
                        listOfNotNull(
                            schedule.programName,
                            schedule.waterTemperature?.let(::temperatureLabel),
                            schedule.scheduledFor,
                        ).joinToString(" · "),
                        fontWeight = FontWeight.Bold,
                    )
                    OutlinedButton(onClick = onCancelSchedule, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text("Avbryt schema")
                    }
                    if (waterTemperatures.isNotEmpty()) {
                        Box(Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = { temperatureMenuOpen = true },
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(selectedTemperature?.let(::temperatureLabel) ?: "Välj temperatur") }
                            DropdownMenu(
                                expanded = temperatureMenuOpen,
                                onDismissRequest = { temperatureMenuOpen = false },
                            ) {
                                waterTemperatures.forEach { temperature ->
                                    DropdownMenuItem(
                                        text = { Text(temperatureLabel(temperature)) },
                                        onClick = {
                                            selectedTemperature = temperature
                                            temperatureMenuOpen = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Box(Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { programMenuOpen = true },
                            enabled = programs.isNotEmpty() && !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(selectedProgram?.name ?: "Hämta programlista") }
                        DropdownMenu(expanded = programMenuOpen, onDismissRequest = { programMenuOpen = false }) {
                            programs.forEach { program ->
                                DropdownMenuItem(
                                    text = { Text(program.name) },
                                    onClick = { selectedProgram = program; programMenuOpen = false },
                                )
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = scheduleDate,
                            onValueChange = { scheduleDate = it },
                            label = { Text("Datum") },
                            singleLine = true,
                            modifier = Modifier.weight(1.4f),
                        )
                        OutlinedTextField(
                            value = scheduleTime,
                            onValueChange = { scheduleTime = it },
                            label = { Text("Tid") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Button(
                        onClick = { confirmSchedule = true },
                        enabled = selectedProgram != null &&
                            state?.remoteControlEnabled == true &&
                            state?.state == "idle" &&
                            !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Schemalägg") }
                }
            }
        }
    }
    if (confirmSchedule) {
        AlertDialog(
            onDismissRequest = { confirmSchedule = false },
            title = { Text("Schemalägg tvätten?") },
            text = {
                Text(
                    "${selectedProgram?.name}, ${selectedTemperature?.let(::temperatureLabel) ?: "maskinens temperatur"}, " +
                        "startar $scheduleDate klockan $scheduleTime. Kontrollera tvätt, tvättmedel, lucka och Smart Control."
                )
            },
            confirmButton = {
                Button(onClick = {
                    val instant = runCatching {
                        ZonedDateTime.of(
                            LocalDate.parse(scheduleDate),
                            LocalTime.parse(scheduleTime),
                            ZoneId.systemDefault(),
                        ).toOffsetDateTime().toString()
                    }.getOrNull()
                    confirmSchedule = false
                    if (instant != null) {
                        selectedProgram?.let {
                            onSchedule(instant, it.code, selectedTemperature)
                        }
                    }
                }) { Text("Bekräfta") }
            },
            dismissButton = { TextButton(onClick = { confirmSchedule = false }) { Text("Avbryt") } },
        )
    }
    pendingConfirmation?.let { command ->
        AlertDialog(
            onDismissRequest = { pendingConfirmation = null },
            title = { Text(if (command == "start") "Starta tvätten?" else "Stoppa tvätten?") },
            text = { Text(
                if (command == "start")
                    "Bekräfta att maskinen är rätt laddad, att tvättmedel finns och att personen hemma vet att den kan starta."
                else
                    "Ett stopp kan lämna tvätten blöt och programmet ofärdigt. Vill du verkligen stoppa?"
            ) },
            confirmButton = {
                Button(onClick = {
                    pendingConfirmation = null
                    onCommand(command, true)
                }) { Text("Ja, ${if (command == "start") "starta" else "stoppa"}") }
            },
            dismissButton = { TextButton(onClick = { pendingConfirmation = null }) { Text("Avbryt") } },
        )
    }
    Text("Senaste 7 dagarna", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Forest)
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF1C7))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column { Text("Klarmarkerade", style = MaterialTheme.typography.bodySmall); Text("${statistics?.cyclesCompleted7d ?: 0}", style = MaterialTheme.typography.headlineSmall, color = Forest) }
                Column(horizontalAlignment = Alignment.End) { Text("Drifttid", style = MaterialTheme.typography.bodySmall); Text("${statistics?.runningMinutes7d ?: 0} min", style = MaterialTheme.typography.headlineSmall, color = Forest) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column { Text("Energi", style = MaterialTheme.typography.bodySmall); Text(number(statistics?.energyUsedKwh7d, " kWh"), style = MaterialTheme.typography.titleLarge, color = Forest) }
                Column(horizontalAlignment = Alignment.End) { Text("Tillgänglighet 24 h", style = MaterialTheme.typography.bodySmall); Text(number(statistics?.availabilityPercent24h, " %"), style = MaterialTheme.typography.titleLarge, color = Forest) }
            }
            Text("Statistiken byggs upp automatiskt när servern observerar riktiga tvättcykler.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun VacuumPanel(
    vacuum: ServerEvent.VacuumState?,
    maps: ServerEvent.VacuumMaps?,
    message: String?,
    mapMessage: String?,
    busy: Boolean,
    mapsBusy: Boolean,
    controlsAvailable: Boolean,
    onRefresh: () -> Unit,
    onRefreshMaps: () -> Unit,
    onCommand: (String, Boolean) -> Unit,
) {
    var pendingVacuumStart by remember { mutableStateOf(false) }
    var pendingVacuumMapping by remember { mutableStateOf(false) }
    Text("Robotdammsugare", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Forest)
    Text("Status, lokala kartor och kontroller samlade på ett ställe.", textAlign = TextAlign.Center, color = Forest)
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.82f))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val vacuumState = when (vacuum?.state) {
                    "idle" -> "Redo"
                    "cleaning" -> "Städar"
                    "paused" -> "Pausad"
                    "returning" -> "På väg hem"
                    "charging" -> "Laddar"
                    "error" -> "Fel"
                    "offline" -> "Offline"
                    else -> "Okänd"
                }
                Text(vacuumState, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(vacuum?.batteryPercent?.let { "$it %" } ?: "—", color = Forest, fontWeight = FontWeight.Bold)
            }
            Text(
                listOfNotNull(
                    vacuum?.cleaningAreaM2?.let { "%.1f m²".format(it) },
                    vacuum?.cleaningTimeMinutes?.let { "$it min" },
                    vacuum?.mapAvailable?.let { if (it) "Karta sparad" else "Ingen karta" },
                    vacuum?.autoEmptyEnabled?.let { if (it) "Autotömning på" else "Autotömning av" },
                ).joinToString(" · ").ifBlank { "Ingen liveinformation ännu" },
                style = MaterialTheme.typography.bodySmall,
            )
            if (vacuum?.systemMessages?.isNotEmpty() == true) {
                vacuum.systemMessages.forEach { warning ->
                    Text("⚠ $warning", color = Copper, fontWeight = FontWeight.Bold)
                }
            } else if (vacuum?.online == true) {
                Text("Inga aktiva systemvarningar.", color = Forest)
            }
            Text(
                "Filter ${vacuum?.filterPercent?.let { "$it %" } ?: "—"} · " +
                    "sidoborste ${vacuum?.sideBrushPercent?.let { "$it %" } ?: "—"} · " +
                    "huvudborste ${vacuum?.mainBrushPercent?.let { "$it %" } ?: "—"}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Råstatus ${vacuum?.rawDeviceStatus ?: "—"}/${vacuum?.rawOperatingMode ?: "—"} · " +
                    "uppgift ${vacuum?.rawTaskStatus ?: "—"} · lokalisering ${vacuum?.rawRelocationStatus ?: "—"}",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = onRefresh, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (busy) "Uppdaterar…" else "Uppdatera dammsugaren")
            }
            if (controlsAvailable) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { pendingVacuumStart = true },
                        enabled = !busy && vacuum?.online == true && vacuum.state in setOf("idle", "charging", "paused"),
                        modifier = Modifier.weight(1f),
                    ) { Text(if (vacuum?.state == "paused") "Fortsätt" else "Starta") }
                    OutlinedButton(
                        onClick = { onCommand("pause", false) },
                        enabled = !busy && vacuum?.online == true && vacuum.state == "cleaning",
                        modifier = Modifier.weight(1f),
                    ) { Text("Pausa") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onCommand("stop", false) },
                        enabled = !busy && vacuum?.online == true && vacuum.state in setOf("cleaning", "paused", "returning"),
                        modifier = Modifier.weight(1f),
                    ) { Text("Stoppa") }
                    OutlinedButton(
                        onClick = { onCommand("return-to-dock", false) },
                        enabled = !busy && vacuum?.online == true && vacuum.state in setOf("idle", "cleaning", "paused", "returning"),
                        modifier = Modifier.weight(1f),
                    ) { Text("Till laddaren") }
                }
                OutlinedButton(
                    onClick = { pendingVacuumMapping = true },
                    enabled = !busy && vacuum?.online == true && vacuum.state in setOf("idle", "charging") && vacuum.mopAttached != true,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (vacuum?.mopAttached == true) "Ta bort moppen först" else "Skapa ny snabbkarta") }
                Text(
                    if (vacuum?.mapAvailable == true && vacuum.multipleMapsEnabled != true)
                        "Aktiverar flerkartsläge och skapar en extrakarta. Den gamla kartan behålls."
                    else "Startar en ny kartläggningsrunda. Befintliga kartor behålls.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            message?.let { Text(it, color = Forest, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
        }
    }
    VacuumMapCard(maps, mapMessage, mapsBusy, onRefreshMaps)
    if (pendingVacuumStart) {
        AlertDialog(
            onDismissRequest = { pendingVacuumStart = false },
            title = { Text(if (vacuum?.state == "paused") "Fortsätta städningen?" else "Starta städningen?") },
            text = { Text("Robotdammsugaren börjar köra i huset. Kontrollera att golvet är fritt från sådant som kan fastna.") },
            confirmButton = {
                Button(onClick = {
                    pendingVacuumStart = false
                    onCommand("start", true)
                }) { Text(if (vacuum?.state == "paused") "Ja, fortsätt" else "Ja, starta") }
            },
            dismissButton = { TextButton(onClick = { pendingVacuumStart = false }) { Text("Avbryt") } },
        )
    }
    if (pendingVacuumMapping) {
        AlertDialog(
            onDismissRequest = { pendingVacuumMapping = false },
            title = { Text("Starta ny kartläggning?") },
            text = { Text("Öppna dörrarna, plocka undan hinder och ta bort moppen. Vid behov aktiveras flerkartsläget automatiskt, sedan kör roboten ut och bygger en extrakarta. Befintliga kartor behålls.") },
            confirmButton = {
                Button(onClick = {
                    pendingVacuumMapping = false
                    onCommand("start-fast-mapping", true)
                }) { Text("Ja, börja kartlägga") }
            },
            dismissButton = { TextButton(onClick = { pendingVacuumMapping = false }) { Text("Avbryt") } },
        )
    }
}

@Composable
private fun VacuumMapCard(
    collection: ServerEvent.VacuumMaps?,
    message: String?,
    busy: Boolean,
    onRefresh: () -> Unit,
) {
    var selectedMap by remember(collection?.maps?.size) { mutableStateOf(0) }
    var threeDimensional by remember { mutableStateOf(true) }
    var rotation by remember { mutableStateOf(35f) }
    val maps = collection?.maps.orEmpty()
    val map = maps.getOrNull(selectedMap.coerceIn(0, (maps.size - 1).coerceAtLeast(0)))
    val roomColors = listOf(
        Color(0xFF88B8A1), Color(0xFFD9A66F), Color(0xFF86A8D6), Color(0xFFC995BD),
        Color(0xFFE0C56E), Color(0xFF75B8BD), Color(0xFFB0A0D4), Color(0xFFA9BD78),
    )

    Text("Kartor", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Forest)
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF17221E))) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(map?.name ?: "Lokal karta", color = Parchment, fontWeight = FontWeight.Bold)
                    Text(
                        if (collection?.offlineReady == true) "Sparad lokalt · redo utan Xiaomi" else "Väntar på lokal arkivering",
                        color = Color(0xFF9FC7B5), style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedButton(onClick = { threeDimensional = !threeDimensional }) { Text(if (threeDimensional) "3D" else "2D") }
            }
            if (maps.size > 1) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    maps.forEachIndexed { index, candidate ->
                        if (index == selectedMap) Button({ selectedMap = index }, Modifier.weight(1f)) { Text(candidate.name) }
                        else OutlinedButton({ selectedMap = index }, Modifier.weight(1f)) { Text(candidate.name) }
                    }
                }
            }
            if (map == null || map.runs.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                    Text(message ?: "Ingen lokalt sparad karta ännu.", color = Parchment, textAlign = TextAlign.Center)
                }
            } else {
                Canvas(
                    Modifier.fillMaxWidth().height(350.dp).pointerInput(threeDimensional) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            if (threeDimensional) rotation = (rotation + drag.x * 0.45f) % 360f
                        }
                    },
                ) {
                    val radians = Math.toRadians(if (threeDimensional) rotation.toDouble() else 0.0)
                    val cosine = cos(radians).toFloat()
                    val sine = sin(radians).toFloat()
                    val vertical = if (threeDimensional) 0.54f else 1f
                    val scale = min(size.width / (map.width.coerceAtLeast(1) * 1.42f), size.height / (map.height.coerceAtLeast(1) * 1.42f))
                    val centerX = map.width / 2f
                    val centerY = map.height / 2f
                    fun project(x: Float, y: Float, lift: Float = 0f): Offset {
                        val dx = x - centerX
                        val dy = y - centerY
                        val rx = dx * cosine - dy * sine
                        val ry = dx * sine + dy * cosine
                        return Offset(size.width / 2f + rx * scale, size.height / 2f + ry * scale * vertical - lift)
                    }
                    fun polygon(points: List<Offset>, color: Color) {
                        if (points.isEmpty()) return
                        drawPath(Path().apply {
                            moveTo(points[0].x, points[0].y)
                            points.drop(1).forEach { lineTo(it.x, it.y) }
                            close()
                        }, color)
                    }
                    map.runs.filterNot { it.wall }.forEach { run ->
                        val base = roomColors[kotlin.math.abs(run.roomId ?: 0) % roomColors.size]
                        polygon(listOf(
                            project(run.x.toFloat(), run.y.toFloat()),
                            project((run.x + run.length).toFloat(), run.y.toFloat()),
                            project((run.x + run.length).toFloat(), run.y + 1f),
                            project(run.x.toFloat(), run.y + 1f),
                        ), if (threeDimensional) base.copy(alpha = 0.94f) else base)
                    }
                    map.runs.filter { it.wall }.forEach { run ->
                        val bottom = listOf(
                            project(run.x.toFloat(), run.y.toFloat()),
                            project((run.x + run.length).toFloat(), run.y.toFloat()),
                            project((run.x + run.length).toFloat(), run.y + 1f),
                            project(run.x.toFloat(), run.y + 1f),
                        )
                        polygon(bottom, Color(0xFF30483E))
                        if (threeDimensional) {
                            val lift = 9f
                            polygon(listOf(bottom[0], bottom[1], bottom[1].copy(y = bottom[1].y - lift), bottom[0].copy(y = bottom[0].y - lift)), Color(0xFF1A2A23))
                            polygon(bottom.map { it.copy(y = it.y - lift) }, Color(0xFF6B897B))
                        }
                    }
                    map.charger?.let { point ->
                        val p = project(point.x.toFloat(), point.y.toFloat(), if (threeDimensional) 7f else 0f)
                        drawCircle(Color(0xFFF1C75B), radius = 7f, center = p)
                        drawCircle(Color.White, radius = 3f, center = p)
                    }
                    map.robot?.let { point ->
                        val p = project(point.x.toFloat(), point.y.toFloat(), if (threeDimensional) 9f else 0f)
                        drawCircle(Color(0xFFEAF4EF), radius = 9f, center = p)
                        drawCircle(Forest, radius = 3f, center = p)
                    }
                }
                if (threeDimensional) {
                    Text("Dra i kartan eller finjustera vinkeln", color = Color(0xFF9FC7B5), style = MaterialTheme.typography.bodySmall)
                    Slider(value = rotation, onValueChange = { rotation = it }, valueRange = 0f..360f)
                }
                if (map.rooms.isNotEmpty()) {
                    Text(map.rooms.joinToString(" · ") { it.name }, color = Parchment, style = MaterialTheme.typography.bodySmall)
                }
            }
            Button(onClick = onRefresh, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (busy) "Läser karta…" else "Uppdatera karta")
            }
            message?.let { Text(it, color = Color(0xFFB9D5C8), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
        }
    }
}

@Composable
private fun PumpPanel(
    configured: List<ServerEvent.ConfiguredPump>,
    state: ServerEvent.PumpState?,
    message: String?,
    busy: Boolean,
    onRefresh: (String?) -> Unit,
    onControl: (String, Boolean?, String?, Int?, String?, String?, Int?) -> Unit,
) {
    fun temperature(value: Double?) = value?.let {
        if (it % 1.0 == 0.0) "${it.toInt()}°" else "${it}°"
    } ?: "—"
    fun mode(value: String?) = when (value) {
        "auto" -> "Auto"
        "heat" -> "Värme"
        "cool" -> "Kyla"
        "dry" -> "Avfuktning"
        "fan" -> "Fläkt"
        "quiet" -> "Tyst"
        else -> value ?: "Okänt"
    }
    val selected = state ?: configured.firstOrNull()?.let {
        ServerEvent.PumpState(it.id, it.name, it.room, false, null, null, null, null, null, null, null, null, true)
    }
    val controlsEnabled = selected?.let { it.online && !it.readOnly && !busy } == true

    Text("Värmepump", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Forest)
    Text("Tydlig status och egna knappar – röststyrning är bara ett komplement.", textAlign = TextAlign.Center, color = Forest)
    if (selected == null) {
        Text(message ?: "Ingen pump tillgänglig. Anslut till servern under Röst.", textAlign = TextAlign.Center)
        OutlinedButton(onClick = { onRefresh(null) }, enabled = !busy) { Text("Försök igen") }
        return
    }

    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.82f))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(selected.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Forest)
                    Text(selected.room, style = MaterialTheme.typography.bodySmall)
                }
                Text(if (selected.online) "● ONLINE" else "○ OFFLINE", color = if (selected.online) Forest else Copper, fontWeight = FontWeight.Bold)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column { Text("Inne", style = MaterialTheme.typography.bodySmall); Text(temperature(selected.roomTemperature), style = MaterialTheme.typography.headlineMedium, color = Forest) }
                Column(horizontalAlignment = Alignment.End) { Text("Ute", style = MaterialTheme.typography.bodySmall); Text(temperature(selected.outdoorTemperature), style = MaterialTheme.typography.headlineMedium, color = Forest) }
            }
            Text("${if (selected.power == true) "På" else if (selected.power == false) "Av" else "Okänd drift"} · ${mode(selected.mode)} · börvärde ${temperature(selected.targetTemperature)}")
            Text("Fläkt ${mode(selected.fanMode)}${selected.powerSelectionPercent?.let { " · effektval $it %" } ?: ""}", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { onRefresh(selected.name) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (busy) "Läser status…" else "Uppdatera status")
            }
            message?.let { Text(it, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(), color = Forest) }
        }
    }

    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF1C7))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("Smarta snabbknappar", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Forest)
            Text("Varje tryck skickas som en avgränsad ändring och räknas som klart först när pumpen har återläst samma värde.", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onControl(selected.name, true, null, null, null, null, null) },
                    enabled = controlsEnabled,
                    modifier = Modifier.weight(1f),
                ) { Text("PÅ") }
                Button(
                    onClick = { onControl(selected.name, false, null, null, null, null, null) },
                    enabled = controlsEnabled,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Copper),
                ) { Text("AV") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf("Auto" to "auto", "Värme" to "heat", "Kyla" to "cool", "Torka" to "dry").forEach { (label, value) ->
                    OutlinedButton(
                        onClick = { onControl(selected.name, true, value, null, null, null, null) },
                        enabled = controlsEnabled,
                        modifier = Modifier.weight(1f),
                    ) { Text(label, style = MaterialTheme.typography.bodySmall) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = { onControl(selected.name, null, null, ((selected.targetTemperature ?: 21.0).toInt() - 1).coerceAtLeast(16), null, null, null) },
                    enabled = controlsEnabled && (selected.targetTemperature ?: 16.0) > 16,
                    modifier = Modifier.weight(1f),
                ) { Text("−") }
                Text(temperature(selected.targetTemperature), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                OutlinedButton(
                    onClick = { onControl(selected.name, null, null, ((selected.targetTemperature ?: 21.0).toInt() + 1).coerceAtMost(30), null, null, null) },
                    enabled = controlsEnabled && (selected.targetTemperature ?: 30.0) < 30,
                    modifier = Modifier.weight(1f),
                ) { Text("+") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf("Borta 16°" to 16, "Natt 19°" to 19, "Komfort 21°" to 21).forEach { (label, value) ->
                    Button(
                        onClick = { onControl(selected.name, true, "heat", value, "auto", null, null) },
                        enabled = controlsEnabled,
                        modifier = Modifier.weight(1f),
                    ) { Text(label, style = MaterialTheme.typography.bodySmall) }
                }
            }
            Text("Fläkt", fontWeight = FontWeight.Bold, color = Forest)
            listOf(listOf("Auto" to "auto", "Tyst" to "quiet", "1" to "1", "2" to "2"), listOf("3" to "3", "4" to "4", "5" to "5")).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    row.forEach { (label, value) ->
                        OutlinedButton(
                            onClick = { onControl(selected.name, null, null, null, value, null, null) },
                            enabled = controlsEnabled,
                            modifier = Modifier.weight(1f),
                        ) { Text(label) }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            Text("Effektgräns", fontWeight = FontWeight.Bold, color = Forest)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf(50, 75, 100).forEach { value ->
                    OutlinedButton(
                        onClick = { onControl(selected.name, null, null, null, null, null, value) },
                        enabled = controlsEnabled,
                        modifier = Modifier.weight(1f),
                    ) { Text("$value %") }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                OutlinedButton(
                    onClick = { onControl(selected.name, null, null, null, null, "vertical", null) },
                    enabled = controlsEnabled,
                    modifier = Modifier.weight(1f),
                ) { Text("Pendla") }
                OutlinedButton(
                    onClick = { onControl(selected.name, null, null, null, null, "not_used", null) },
                    enabled = controlsEnabled,
                    modifier = Modifier.weight(1f),
                ) { Text("Stoppa spjäll") }
            }
            if (!controlsEnabled) {
                Text(
                    when {
                        busy -> "Väntar på pumpens bekräftelse…"
                        selected.readOnly -> "Styrning är låst på pumpservern."
                        !selected.online -> "Styrning kräver att adaptern är online."
                        else -> "Styrning är tillfälligt låst."
                    },
                    color = Copper,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun TvPanel(
    configured: List<ServerEvent.ConfiguredTv>,
    discovered: List<ServerEvent.DiscoveredTv>,
    message: String?,
    busy: Boolean,
    onDiscover: () -> Unit,
    onSave: (String, String, String) -> Unit,
    onCommand: (String, String) -> Unit,
) {
    var manualHost by remember { mutableStateOf("") }
    var manualName by remember { mutableStateOf("TV") }
    var manualRoom by remember { mutableStateOf("vardagsrummet") }

    Text("NEC-TV", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Forest)
    Text("Servern styr TV:n på lokalnätet, även när telefonen använder 5G.", textAlign = TextAlign.Center)
    Button(onClick = onDiscover, enabled = !busy) { Text(if (busy) "Arbetar…" else "Sök på lokalnätet") }
    message?.let { Text(it, color = Forest, textAlign = TextAlign.Center) }

    discovered.filter { candidate -> configured.none { it.host == candidate.host } }.forEach { candidate ->
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.72f))) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Hittad: ${candidate.host}:${candidate.port}", fontWeight = FontWeight.Bold, color = Forest)
                OutlinedTextField(manualName, { manualName = it }, label = { Text("Namn") }, singleLine = true)
                OutlinedTextField(manualRoom, { manualRoom = it }, label = { Text("Rum") }, singleLine = true)
                Button(onClick = { onSave(candidate.host, manualName, manualRoom) }, enabled = manualName.isNotBlank() && manualRoom.isNotBlank() && !busy) { Text("Spara i tvs.toml") }
            }
        }
    }

    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.72f))) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Lägg till med IP", fontWeight = FontWeight.Bold, color = Forest)
            OutlinedTextField(manualHost, { manualHost = it }, label = { Text("Privat IPv4-adress") }, placeholder = { Text("192.168.32.1") }, singleLine = true)
            OutlinedTextField(manualName, { manualName = it }, label = { Text("Namn") }, singleLine = true)
            OutlinedTextField(manualRoom, { manualRoom = it }, label = { Text("Rum") }, singleLine = true)
            OutlinedButton(onClick = { onSave(manualHost, manualName, manualRoom) }, enabled = manualHost.isNotBlank() && manualName.isNotBlank() && manualRoom.isNotBlank() && !busy) { Text("Spara TV") }
        }
    }

    configured.forEach { tv ->
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.82f))) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tv.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Forest)
                Text("${tv.room} · ${tv.host}:${tv.port}", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { onCommand(tv.name, "power_on") }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("PÅ") }
                    Button(onClick = { onCommand(tv.name, "power_off") }, enabled = !busy, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Copper)) { Text("AV") }
                }
                listOf(
                    "HDMI 1" to "input_hdmi1", "HDMI 2" to "input_hdmi2", "HDMI 3" to "input_hdmi3",
                    "VGA RGB" to "input_vga_rgb", "VGA Comp" to "input_vga_component", "A/V" to "input_av",
                ).chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { (label, command) ->
                            OutlinedButton(onClick = { onCommand(tv.name, command) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text(label) }
                        }
                    }
                }
            }
        }
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
    onStartMusic: (List<MagicHomeDevice>, Int, Int, Int, Int) -> Unit,
    onStopMusic: () -> Unit,
    onWifiProvision: (String, String, () -> Unit) -> Unit,
    hasMicrophonePermission: Boolean,
    onRequestMicrophonePermission: () -> Unit,
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
    var musicTargets by remember { mutableStateOf(emptySet<String>()) }
    var musicSensitivity by remember { mutableStateOf(55f) }
    var musicColor by remember { mutableStateOf(Triple(160, 0, 255)) }

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

    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFE4DDCB))) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Musikljus", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Forest)
            Text(
                "Telefonens mikrofon mäter bara basnivån lokalt. Rått ljud skickas eller sparas aldrig.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("Mikrofonkälla: Den här telefonen", fontWeight = FontWeight.Bold, color = Forest)
            if (wifiState.devices.isEmpty()) {
                Text("Sök efter Wi-Fi-ljus först.")
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Ljus att synkronisera", fontWeight = FontWeight.Bold)
                    TextButton(onClick = {
                        musicTargets = if (musicTargets.size == wifiState.devices.size) {
                            emptySet()
                        } else {
                            wifiState.devices.map { it.mac }.toSet()
                        }
                    }) { Text(if (musicTargets.size == wifiState.devices.size) "Avmarkera" else "Välj alla") }
                }
                wifiState.devices.forEach { device ->
                    val configured = configuredLights.firstOrNull {
                        it.mac.filter(Char::isLetterOrDigit).equals(device.mac.filter(Char::isLetterOrDigit), true)
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = device.mac in musicTargets,
                            onCheckedChange = { checked ->
                                musicTargets = if (checked) musicTargets + device.mac else musicTargets - device.mac
                            },
                            enabled = !wifiState.musicReactive,
                        )
                        Text(configured?.let { "${it.name} · ${it.room}" } ?: "${device.model} · ${device.ip}")
                    }
                }
                Text("Känslighet ${musicSensitivity.toInt()} %")
                Slider(
                    value = musicSensitivity,
                    onValueChange = { musicSensitivity = it },
                    valueRange = 1f..100f,
                    enabled = !wifiState.musicReactive,
                )
                Text("Basfärg", fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ColorPreset("Röd", Color(0xFFD43A35), Modifier.weight(1f), !wifiState.musicReactive) {
                        musicColor = Triple(255, 0, 0)
                    }
                    ColorPreset("Blå", Color(0xFF315DCC), Modifier.weight(1f), !wifiState.musicReactive) {
                        musicColor = Triple(0, 80, 255)
                    }
                    ColorPreset("Lila", Color(0xFF8D35C7), Modifier.weight(1f), !wifiState.musicReactive) {
                        musicColor = Triple(160, 0, 255)
                    }
                    ColorPreset("Vit", Color(0xFF777777), Modifier.weight(1f), !wifiState.musicReactive) {
                        musicColor = Triple(255, 255, 255)
                    }
                }
                if (!hasMicrophonePermission) {
                    Button(onClick = onRequestMicrophonePermission) { Text("Tillåt musikmikrofon") }
                } else if (wifiState.musicReactive) {
                    Text("● Mikrofon aktiv · ${wifiState.musicTargetCount} ljus", color = Copper, fontWeight = FontWeight.Bold)
                    LinearProgressIndicator(
                        progress = { wifiState.musicLevel.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(onClick = onStopMusic, colors = ButtonDefaults.buttonColors(containerColor = Copper)) {
                        Text("Stoppa musikljus")
                    }
                } else {
                    Button(
                        onClick = {
                            val selected = wifiState.devices.filter { it.mac in musicTargets }
                            onStartMusic(
                                selected,
                                musicColor.first,
                                musicColor.second,
                                musicColor.third,
                                musicSensitivity.toInt(),
                            )
                        },
                        enabled = musicTargets.isNotEmpty(),
                    ) { Text("Starta bas-puls") }
                }
            }
        }
    }

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
