package se.euther.euthervox

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { EutherVoxApp() } }
    }
}

private val Forest = Color(0xFF254C3A)
private val Copper = Color(0xFFB86035)
private val Parchment = Color(0xFFF2EBDD)

@Composable
fun EutherVoxApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember { VoiceController(context, scope) }
    val state by controller.state.collectAsStateWithLifecycle()
    val preferences = remember { context.getSharedPreferences("euthervox", 0) }
    var address by remember { mutableStateOf(preferences.getString("server_address", "").orEmpty()) }
    var nodeName by remember { mutableStateOf(preferences.getString("node_name", "android-phone").orEmpty()) }
    var username by remember { mutableStateOf(preferences.getString("username", "").orEmpty()) }
    var voiceId by remember { mutableStateOf(preferences.getString("voice_id", "piper-nst").orEmpty()) }
    var settingsAddress by remember { mutableStateOf(address) }
    var settingsNodeName by remember { mutableStateOf(nodeName) }
    var settingsUsername by remember { mutableStateOf(username) }
    var settingsVoiceId by remember { mutableStateOf(voiceId) }
    var settingsPassword by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(address.isBlank()) }
    var hasPermission by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> hasPermission = granted }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) controller.onPause() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); controller.disconnect() }
    }

    Surface(color = Parchment, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.size(92.dp).background(Forest, CircleShape), contentAlignment = Alignment.Center) {
                Text("⛏", style = MaterialTheme.typography.displayMedium, color = Parchment)
            }
            Text("Skinnskattaren", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Forest)
            Text(
                "Röst: " + when (voiceId) {
                    "piper-lisa" -> "Lisa"
                    "chatterbox" -> "Chatterbox"
                    else -> "NST"
                },
                style = MaterialTheme.typography.bodySmall,
                color = Forest,
            )
            Text(state.connectionLabel, color = if (state.canTalk) Forest else Copper)
            Text(state.serverAddress.ifBlank { "Ingen server vald" }, style = MaterialTheme.typography.bodySmall)

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { controller.connect(address, nodeName, username, requestedVoiceId = voiceId) }, enabled = address.isNotBlank()) { Text("Anslut") }
                OutlinedButton(onClick = {
                    settingsAddress = address
                    settingsNodeName = nodeName
                    settingsUsername = username
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
                        state.interruptionListening -> "Samtalsläge: du kan avbryta Skinnskattaren genom att tala"
                        state.microphoneActive -> "Samtalsläge: lyssnar efter din röst"
                        else -> "Samtalsläge: Skinnskattaren svarar"
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
            TranscriptCard("Skinnskattaren", state.responseText)
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
                Text("Skinnskattarens röst", fontWeight = FontWeight.Bold, color = Forest)
                VoiceChoice("piper-nst", "NST – snabb (rekommenderad)", settingsVoiceId) { settingsVoiceId = it }
                VoiceChoice("piper-lisa", "Lisa – alternativ", settingsVoiceId) { settingsVoiceId = it }
                VoiceChoice("chatterbox", "Chatterbox – långsam (experimentell)", settingsVoiceId) { settingsVoiceId = it }
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
                val savedVoiceId = settingsVoiceId
                preferences.edit(commit = true) {
                    putString("server_address", savedAddress)
                    putString("node_name", savedNodeName)
                    putString("username", savedUsername)
                    putString("voice_id", savedVoiceId)
                }
                address = savedAddress
                nodeName = savedNodeName
                username = savedUsername
                voiceId = savedVoiceId
                showSettings = false
                controller.connect(savedAddress, savedNodeName, savedUsername, settingsPassword, savedVoiceId)
                settingsPassword = ""
            }, enabled = settingsAddress.isNotBlank()) { Text("Spara och stäng") }
        },
        dismissButton = {
            TextButton(onClick = { showSettings = false }) { Text("Avbryt") }
        },
    )
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
