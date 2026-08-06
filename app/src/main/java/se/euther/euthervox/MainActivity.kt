package se.euther.euthervox

import android.Manifest
import android.content.pm.PackageManager
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
    var password by remember { mutableStateOf("") }
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
            Text(state.connectionLabel, color = if (state.canTalk) Forest else Copper)
            Text(state.serverAddress.ifBlank { "Ingen server vald" }, style = MaterialTheme.typography.bodySmall)

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { controller.connect(address, nodeName, username) }, enabled = address.isNotBlank()) { Text("Anslut") }
                OutlinedButton(onClick = { showSettings = true }) { Text("Inställningar") }
            }

            if (!hasPermission) {
                Button(onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }) { Text("Tillåt mikrofon") }
            }

            PushToTalkButton(
                active = state.microphoneActive,
                enabled = state.canTalk && hasPermission,
                onStart = controller::startTalking,
                onStop = controller::stopTalking,
            )
            Text(state.status.name, style = MaterialTheme.typography.titleMedium, color = if (state.status == VoiceStatus.Error) Color.Red else Forest)
            if (state.errorMessage != null) Text(state.errorMessage!!, color = Color.Red, textAlign = TextAlign.Center)
            if (state.droppedCaptureFrames > 0) Text("Tappade mikrofonblock: ${state.droppedCaptureFrames}", color = Color.Red)

            TranscriptCard("Preliminärt", state.partialTranscript)
            TranscriptCard("Du sade", state.finalTranscript)
            TranscriptCard("Skinnskattaren", state.responseText)
            LatencyCard(state.latencies)

            OutlinedButton(onClick = controller::cancelResponse, enabled = state.status == VoiceStatus.Speaking || state.status == VoiceStatus.Processing) {
                Text("Avbryt uppspelning")
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    if (showSettings) AlertDialog(
        onDismissRequest = { if (address.isNotBlank()) showSettings = false },
        title = { Text("Anslutning") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(address, { address = it }, label = { Text("Serveradress") }, placeholder = { Text("wss://apothictech.se/euthervox/ws") }, singleLine = true)
                OutlinedTextField(nodeName, { nodeName = it }, label = { Text("Nodnamn") }, singleLine = true)
                OutlinedTextField(username, { username = it }, label = { Text("EutherOxide-användare") }, singleLine = true)
                OutlinedTextField(
                    password,
                    { password = it },
                    label = { Text("Lösenord (endast första gången)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                Text("Vid wss:// växlas lösenordet mot en app-token som krypteras med Android Keystore. Lösenordet sparas aldrig. ws:// ska endast användas på betrott LAN.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { password = ""; controller.forgetCredentials() }) { Text("Glöm sparad inloggning") }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                preferences.edit {
                    putString("server_address", address.trim())
                    putString("node_name", nodeName.trim())
                    putString("username", username.trim())
                }
                showSettings = false
                controller.connect(address, nodeName, username, password)
                password = ""
            }, enabled = address.isNotBlank()) { Text("Spara och anslut") }
        },
    )
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
