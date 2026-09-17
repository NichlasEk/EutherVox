package se.euther.euthervox

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Canvas
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import se.euther.euthervox.app.VoiceController
import se.euther.euthervox.app.VoiceUiState

@Composable
fun RobotMusicPanel(state: VoiceUiState, controller: VoiceController) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var cleaning by rememberSaveable { mutableStateOf(false) }
    var volume by rememberSaveable { mutableStateOf(50f) }
    val music = state.robotMusic
    val phase = music?.get("state")?.asString ?: "stopped"
    val busy by rememberUpdatedState(state.robotMusicBusy)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(expanded, lifecycle) {
        if (expanded) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) { if (!busy) controller.robotMusic("status"); delay(3000) }
        }
    }
    LaunchedEffect(music?.get("volume")) { music?.get("volume")?.asFloat?.let { volume = it } }
    Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Ebba som jukebox", style = MaterialTheme.typography.headlineSmall)
            OutlinedButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
                Text(if (expanded) "Dölj musiken" else "Öppna musiken")
            }
            if (expanded) {
                Text("Spela en låt eller spellista medan Ebba står still eller städar. Musiken fortsätter när du lämnar appen.")
                OutlinedTextField(value = query, onValueChange = { query = it.take(300) }, label = { Text("Låt, artist eller länk till låt/spellista") }, modifier = Modifier.fillMaxWidth())
                Row {
                    Checkbox(checked = cleaning, onCheckedChange = { cleaning = it })
                    Text("Tillåt musik under städning", Modifier.padding(top = 12.dp))
                }
                Text("Läget väljs när du startar låten. Starta städningen med de vanliga kontrollerna. Musiken stängs när hon har städat och dockar.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { controller.robotMusic("play", query, cleaning, volume.toInt()) }, enabled = query.isNotBlank() && state.vacuumControlsAvailable && !state.robotMusicBusy, modifier = Modifier.fillMaxWidth()) { Text("Spela på Ebba") }
                music?.get("title")?.asString?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                val queueCount = music?.get("queue_count")?.asInt ?: 0
                val queueIndex = music?.get("queue_index")?.asInt ?: 0
                if (queueCount > 1) {
                    Text("${music?.get("queue_title")?.asString ?: "Spellista"} · $queueIndex av $queueCount", style = MaterialTheme.typography.titleSmall)
                    music?.getAsJsonArray("queue")?.drop(queueIndex)?.take(3)?.forEachIndexed { index, item ->
                        Text("${queueIndex + index + 1}. ${item.asJsonObject.get("title").asString}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                state.robotMusicMessage?.let { Text(it) }
                val enabled = state.vacuumControlsAvailable && !state.robotMusicBusy
                val duration = music?.get("duration_seconds")?.takeUnless { it.isJsonNull }?.asFloat ?: 0f
                val position = music?.get("position_seconds")?.asFloat ?: 0f
                var seekPreview by remember(music?.get("playback_id")?.asString) { mutableStateOf<Float?>(null) }
                var seekTrackId by remember { mutableStateOf<String?>(null) }
                var dragging by remember { mutableStateOf(false) }
                LaunchedEffect(position, busy) { if (!dragging && !busy) seekPreview = null }
                Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    Slider(
                        value = (seekPreview ?: position).coerceIn(0f, duration.coerceAtLeast(1f)),
                        onValueChange = { if (!dragging) seekTrackId = music?.get("playback_id")?.asString; dragging = true; seekPreview = it },
                        onValueChangeFinished = {
                            dragging = false
                            seekPreview?.let { target -> controller.robotMusic("seek", positionSeconds = target.toInt().coerceIn(0, (duration.toInt() - 1).coerceAtLeast(0)), playbackId = seekTrackId) }
                        },
                        enabled = enabled && duration > 0 && phase in setOf("playing", "paused"),
                        valueRange = 0f..duration.coerceAtLeast(1f),
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Spola i låten" }
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(musicTime(seekPreview ?: position), style = MaterialTheme.typography.labelSmall)
                        Text(if (duration > 0) musicTime(duration) else "Längd okänd", style = MaterialTheme.typography.labelSmall)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        IconButton(onClick = { controller.robotMusic("previous") }, enabled = enabled && music?.get("has_previous")?.asBoolean == true, modifier = Modifier.semantics { contentDescription = "Föregående låt" }) { MusicControlIcon("previous") }
                        IconButton(onClick = { controller.robotMusic(if (phase == "paused") "resume" else "pause") }, enabled = enabled && phase in setOf("playing", "paused", "loading"), modifier = Modifier.semantics { contentDescription = if (phase == "paused") "Fortsätt" else "Pausa" }) { MusicControlIcon(if (phase == "paused") "play" else "pause") }
                        IconButton(onClick = { controller.robotMusic("next") }, enabled = enabled && music?.get("has_next")?.asBoolean == true, modifier = Modifier.semantics { contentDescription = "Nästa låt" }) { MusicControlIcon("next") }
                        IconButton(onClick = { controller.robotMusic("stop") }, enabled = state.vacuumControlsAvailable, modifier = Modifier.semantics { contentDescription = "Stoppa musik" }) { MusicControlIcon("stop") }
                    }
                }
                Text("Musikvolym ${volume.toInt()} %")
                Slider(value = volume, onValueChange = { volume = it }, valueRange = 0f..100f, onValueChangeFinished = { controller.robotMusic("volume", volume = volume.toInt()) })
                Text("Robotens egna meddelanden, budbäraren och pratknappen pausar musiken. Tryck Fortsätt efteråt. Offentliga och olistade spellistor: upp till 50 låtar, högst 20 minuter per låt. Stoppa musik tömmer kön.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun musicTime(seconds: Float): String {
    val total = seconds.toInt().coerceAtLeast(0)
    return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
}

@Composable
private fun MusicControlIcon(kind: String) {
    val tint = LocalContentColor.current
    Canvas(Modifier.size(22.dp)) {
        val u = size.width / 22f
        fun rectangle(x: Float, y: Float, w: Float, h: Float) = drawRect(tint, Offset(x*u, y*u), Size(w*u, h*u))
        fun triangle(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
            drawPath(Path().apply { moveTo(x1*u,y1*u); lineTo(x2*u,y2*u); lineTo(x3*u,y3*u); close() }, tint)
        }
        when (kind) {
            "previous" -> { rectangle(3f,4f,3f,14f); triangle(18f,4f,7f,11f,18f,18f) }
            "next" -> { triangle(4f,4f,15f,11f,4f,18f); rectangle(16f,4f,3f,14f) }
            "play" -> triangle(5f,3f,19f,11f,5f,19f)
            "pause" -> { rectangle(5f,3f,4f,16f); rectangle(13f,3f,4f,16f) }
            else -> rectangle(4f,4f,14f,14f)
        }
    }
}
