package se.euther.euthervox

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
                    OutlinedButton(onClick = { controller.robotMusic("next") }, enabled = music?.get("has_next")?.asBoolean == true && state.vacuumControlsAvailable && !state.robotMusicBusy, modifier = Modifier.fillMaxWidth()) { Text("Nästa låt") }
                }
                state.robotMusicMessage?.let { Text(it) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { controller.robotMusic(if (phase == "paused") "resume" else "pause") }, enabled = phase in setOf("playing", "paused", "loading") && !state.robotMusicBusy, modifier = Modifier.weight(1f)) { Text(if (phase == "paused") "Fortsätt" else "Pausa") }
                    OutlinedButton(onClick = { controller.robotMusic("stop") }, enabled = state.vacuumControlsAvailable, modifier = Modifier.weight(1f)) { Text("Stoppa musik") }
                }
                Text("Musikvolym ${volume.toInt()} %")
                Slider(value = volume, onValueChange = { volume = it }, valueRange = 0f..100f, onValueChangeFinished = { controller.robotMusic("volume", volume = volume.toInt()) })
                Text("Robotens egna meddelanden, budbäraren och pratknappen pausar musiken. Tryck Fortsätt efteråt. Offentliga och olistade spellistor: upp till 50 låtar, högst 20 minuter per låt. Stoppa musik tömmer kön.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
