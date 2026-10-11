package se.euther.eutherbeam

import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import se.euther.eutherbeam.androidtv.AndroidTvDeviceStore
import se.euther.eutherbeam.androidtv.bindPlayer
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun RoomRemoteCards(enabled: Boolean = true, status: String? = null, onVolume: ((String, String) -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val remote = remember { BeamVoice(context.applicationContext) }
    val playerStore = remember { AndroidTvDeviceStore(context) }
    val preferences = remember { context.getSharedPreferences("eutherbeam", android.content.Context.MODE_PRIVATE) }
    var players by remember { mutableStateOf(playerStore.load()) }
    DisposableEffect(preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> players = playerStore.load() }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    var lastTap by remember { mutableStateOf(emptyMap<String, Long>()) }
    fun volume(target: String, direction: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - (lastTap[target] ?: -160L) < 160) return
        lastTap = lastTap + (target to now)
        if (onVolume != null) {
            onVolume(target, direction)
        } else {
            context.sendBroadcast(Intent("se.euther.euthervox.LOGITECH_VOLUME_TARGET")
                .setComponent(ComponentName("se.euther.euthervox", "se.euther.euthervox.background.LogitechVolumeReceiver"))
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                .putExtra("target_id", target).putExtra("direction", direction))
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf("samsung" to "Uppe", "nec" to "Nere").forEach { (target, room) ->
            var working by remember(target) { mutableStateOf(false) }
            var failed by remember(target) { mutableStateOf(false) }
            var feedback by remember(target) { mutableStateOf<String?>(null) }
            fun command(device: String, operation: String) {
                if (working) return
                working = true
                feedback = null
                failed = false
                scope.launch {
                    runCatching { remote.execute(device, operation) }
                        .onFailure { failed = true; feedback = it.message ?: "Kunde inte nå enheten" }
                    working = false
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(room, style = MaterialTheme.typography.titleLarge)
                    Text(if (target == "nec") "NEC" else "Samsung", style = MaterialTheme.typography.bodyMedium)
                    val linked = players.filter { it.linkedDisplay == target }
                    Text(when (linked.size) {
                        0 -> "Ingen puck kopplad"
                        1 -> linked.single().name + if (linked.single().paired) " · Parad" else " · Behöver paras"
                        else -> "Välj vilken puck som hör till TV:n"
                    })
                    var choosePlayer by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { choosePlayer = true }) { Text("Välj puck") }
                        DropdownMenu(expanded = choosePlayer, onDismissRequest = { choosePlayer = false }) {
                            DropdownMenuItem(text = { Text("Ingen puck") }, onClick = {
                                playerStore.save(bindPlayer(playerStore.load(), target, null)); choosePlayer = false
                            })
                            players.forEach { player ->
                                val selectable = player.linkedDisplay.isBlank() || player.linkedDisplay == target
                                DropdownMenuItem(text = { Text(player.name + if (selectable) "" else " · Kopplad till andra TV:n") },
                                    enabled = selectable, onClick = {
                                        runCatching { playerStore.save(bindPlayer(playerStore.load(), target, player.id)) }
                                            .onFailure { feedback = it.message }
                                        choosePlayer = false
                                    })
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("power_on" to "TV på", "power_off" to "TV av").forEach { (operation, label) ->
                            OutlinedButton(onClick = { command(target, operation) }, enabled = !working,
                                modifier = Modifier.weight(1f).semantics { contentDescription = "$room: $label" }) { Text(label) }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("hdmi1" to "HDMI 1", "hdmi2" to "HDMI 2").forEach { (operation, label) ->
                            OutlinedButton(onClick = { command(target, operation) }, enabled = !working,
                                modifier = Modifier.weight(1f).semantics { contentDescription = "$room: $label" }) { Text(label) }
                        }
                    }
                    Text("Fjärrkontroll", style = MaterialTheme.typography.titleMedium)
                    listOf(
                        listOf("up" to "↑"),
                        listOf("left" to "←", "ok" to "OK", "right" to "→"),
                        listOf("down" to "↓"),
                        listOf("back" to "Tillbaka", "home" to "Hem"),
                        listOf("pause" to "Pausa", "play" to "Spela"),
                    ).forEach { buttons ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            buttons.forEach { (operation, label) ->
                                val spokenLabel = mapOf("up" to "Upp", "down" to "Ner", "left" to "Vänster", "right" to "Höger")[operation] ?: label
                                OutlinedButton(onClick = { command("android_tv_$target", operation) }, enabled = !working,
                                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "$room: $spokenLabel" }) { Text(label) }
                            }
                        }
                    }
                    TextButton(onClick = {
                        if (!working) {
                            working = true
                            scope.launch {
                                failed = false
                                feedback = runCatching { remote.status("android_tv_$target") }.getOrElse { failed = true; it.message ?: "Ingen uppspelningsinformation" }
                                working = false
                            }
                        }
                    }, enabled = !working) { Text("Vad visas?") }
                    HorizontalDivider()
                    Text("Volym", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        listOf("down" to "−", "up" to "+").forEach { (direction, symbol) ->
                            Button(onClick = {
                                feedback = null
                                runCatching { volume(target, direction) }.onFailure { feedback = "Öppna och anslut EutherVox för volymstyrning." }
                            }, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 56.dp).semantics {
                                contentDescription = "$room: " + if (direction == "up") "Höj volymen" else "Sänk volymen"
                            }) { Text(symbol, style = MaterialTheme.typography.headlineSmall) }
                        }
                    }
                    feedback?.let { Text(it, color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        status?.takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
