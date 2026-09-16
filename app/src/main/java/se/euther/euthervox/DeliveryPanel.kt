package se.euther.euthervox

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import com.google.gson.JsonObject
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import se.euther.euthervox.app.VoiceController
import se.euther.euthervox.app.VoiceUiState
import java.util.UUID
import kotlin.math.min

private fun JsonObject.num(key: String, default: Double = 0.0) = get(key)?.takeUnless { it.isJsonNull }?.asDouble ?: default
private fun JsonObject.str(key: String) = get(key)?.takeUnless { it.isJsonNull }?.asString ?: ""

@Composable
fun DeliveryPanel(state: VoiceUiState, controller: VoiceController) {
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> controller.stopRobotTalk(if (granted) "Mikrofonen är tillåten. Håll inne för att prata." else "Mikrofontillstånd behövs för att prata genom Ebba.") }
    var stay by rememberSaveable { mutableStateOf(false) }
    DisposableEffect(controller) { onDispose { controller.stopRobotTalk() } }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf("") }
    val map = state.deliveryMap
    val hash = map?.str("map_hash") ?: ""
    var target by remember(hash) { mutableStateOf<Offset?>(null) }
    var confirm by remember { mutableStateOf(false) }
    // Preserve this key across uncertain responses and activity recreation.
    var requestId by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    val job = state.deliveryJob
    val phase = job?.str("phase") ?: ""
    val active = phase.isNotEmpty() && phase !in setOf("done", "cancelled", "failed", "parked")
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(expanded, lifecycle) {
        if (expanded) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) { controller.delivery("current"); delay(3000) }
        }
    }
    LaunchedEffect(job?.str("id")) {
        if (job?.str("id") == requestId) requestId = UUID.randomUUID().toString()
    }
    Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Ebba som budbärare", style = MaterialTheme.typography.headlineSmall)
            Text("Skicka ett meddelande med Ebbas röst till en plats på kartan.")
            OutlinedButton(onClick = { if (expanded) controller.stopRobotTalk(); expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
                Text(if (expanded) "Dölj budbäraren" else "Öppna budbäraren")
            }
            if (expanded) {
                Text("För leverans: starta från dockan. Välj ett mål på fri golvyta. Ebba kan köra borsten på vägen, stannar och pratar. Välj om hon ska stanna kvar efteråt.")
                OutlinedButton(onClick = { controller.delivery("map") }, enabled = !state.deliveryBusy && !active, modifier = Modifier.fillMaxWidth()) {
                    Text(if (map == null) "Hämta aktuell karta" else "Hämta kartan igen")
                }
                if (map != null) {
                    DeliveryMap(map, job, if (active && job != null && job.str("kind") != "speech") Offset(job.num("target_x").toFloat(), job.num("target_y").toFloat()) else target, !active && !state.deliveryBusy) { target = it; requestId = UUID.randomUUID().toString() }
                    Text(if (target == null) "Nyp för zoom, dra för att flytta. Tryck på fri golvyta för att välja mål." else "Mål markerat i orange. Ebba visas i lila.")
                }
                OutlinedTextField(value = text, onValueChange = { text = it.take(400); requestId = UUID.randomUUID().toString() },
                    enabled = !active && !state.deliveryBusy, label = { Text(if (stay) "Meddelande före pratläget (valfritt)" else "Vad ska Ebba säga?") },
                    supportingText = { Text("${text.length}/400 · högst 30 sekunder tal") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                Button(onClick = {
                    controller.stopRobotTalk()
                    controller.delivery("speak", com.google.gson.JsonObject().apply {
                        addProperty("request_id", requestId); addProperty("text", text.trim())
                    })
                }, enabled = text.isNotBlank() && !active && !state.deliveryBusy && state.vacuumControlsAvailable,
                    modifier = Modifier.fillMaxWidth()) { Text("Säg med Ebbas röst här") }
                Text("Läser upp texten där Ebba står, utan att köra. Fungerar även i dockan.", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth()) {
                    Checkbox(checked = stay, onCheckedChange = { stay = it; requestId = UUID.randomUUID().toString() }, enabled = !active && !state.deliveryBusy)
                    Text("Stanna här efter leveransen", modifier = Modifier.padding(top = 12.dp))
                }
                Button(onClick = { confirm = true }, enabled = map != null && target != null && (text.isNotBlank() || stay) && !active && !state.deliveryBusy && state.vacuumControlsAvailable,
                    modifier = Modifier.fillMaxWidth()) { Text("Skicka Ebba") }
                state.deliveryMessage?.let { Text(it) }
                job?.let { Text(it.str("message"), style = MaterialTheme.typography.titleMedium)
                    it.get("warning")?.takeUnless { v -> v.isJsonNull }?.asString?.takeIf { v -> v.isNotBlank() }?.let { warning -> Text(warning) }
                }
                if (!active && state.vacuumControlsAvailable) {
                    val canTalk = phase == "parked" || (state.vacuumState?.online == true && state.vacuumState.state in setOf("idle", "paused", "charging"))
                    Surface(color = if (state.robotTalking) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                        contentColor = if (state.robotTalking) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onPrimary, shape = MaterialTheme.shapes.large,
                        modifier = Modifier.fillMaxWidth().pointerInput(canTalk) {
                            detectTapGestures(onPress = {
                                if (canTalk) {
                                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                        controller.startRobotTalk()
                                        try { tryAwaitRelease() } finally { controller.stopRobotTalk() }
                                    } else permission.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            })
                        }) {
                        Text(if (!canTalk) "Ebba måste stå still och vara ansluten" else if (state.robotTalking) "Släpp för att stänga mikrofonen" else "Håll inne för att prata genom Ebba", modifier = Modifier.padding(20.dp))
                    }
                    Text(state.robotTalkMessage ?: "Din egen röst strömmas när Ebba står still. Högst 28 sekunder per tryck.")
                    OutlinedButton(onClick = { controller.stopRobotTalk(); controller.controlVacuum("return-to-dock") }, enabled = !state.vacuumBusy && state.vacuumState?.state != "charging", modifier = Modifier.fillMaxWidth()) { Text("Kör hem") }
                }
                if (active) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    OutlinedButton(onClick = { controller.delivery("cancel", JsonObject().apply { addProperty("home", false) }) }, enabled = !state.deliveryBusy, modifier = Modifier.fillMaxWidth()) { Text("Avbryt och stanna") }
                    if (job?.str("kind") != "speech") OutlinedButton(onClick = { controller.delivery("cancel", JsonObject().apply { addProperty("home", true) }) }, enabled = !state.deliveryBusy, modifier = Modifier.fillMaxWidth()) { Text("Avbryt och åk hem") }
                }
            }
        }
    }
    if (confirm && map != null && target != null) AlertDialog(
        onDismissRequest = { confirm = false }, title = { Text("Skicka Ebba?") },
        text = { Text("Ebba kör till den markerade platsen.${if (text.isNotBlank()) "\n\nHon säger: $text" else ""}\n\n${if (stay) "Sedan står hon kvar så att du kan prata genom henne." else "Sedan återvänder hon till laddaren."} Uppdraget fortsätter även om appen stängs.") },
        confirmButton = { TextButton(onClick = {
            confirm = false
            controller.delivery("create", JsonObject().apply {
                addProperty("request_id", requestId); addProperty("map_id", map.num("map_id").toInt()); addProperty("map_hash", hash)
                addProperty("x", target!!.x); addProperty("y", target!!.y); addProperty("text", text.trim()); addProperty("confirmed", true); addProperty("stay", stay)
            })
        }) { Text("Ja, skicka") } }, dismissButton = { TextButton(onClick = { confirm = false }) { Text("Nej") } })
}

@Composable
private fun DeliveryMap(map: JsonObject, job: JsonObject?, target: Offset?, selectable: Boolean, onTarget: (Offset) -> Unit) {
    val width = map.num("width").toFloat(); val height = map.num("height").toFloat()
    val resolution = map.num("resolution").toFloat(); val left = map.num("left").toFloat(); val bottom = map.num("bottom").toFloat()
    val runs = remember(map) { map.getAsJsonArray("runs").map { r -> r.asJsonArray.map { it.asInt } } }
    var zoom by remember(map.str("map_hash")) { mutableFloatStateOf(1f) }
    var pan by remember(map.str("map_hash")) { mutableStateOf(Offset.Zero) }
    var canvas by remember { mutableStateOf(Size(1f, 1f)) }
    fun scale() = min(canvas.width / width, canvas.height / height) * .95f * zoom
    fun origin(): Offset = Offset((canvas.width - width * scale()) / 2, (canvas.height - height * scale()) / 2) + pan
    val updatedSelectable by rememberUpdatedState(selectable)
    val updatedTarget by rememberUpdatedState(onTarget)
    Canvas(Modifier.fillMaxWidth().height(350.dp).clipToBounds().onSizeChanged { canvas = Size(it.width.toFloat(), it.height.toFloat()) }
        .pointerInput(map.str("map_hash")) {
            detectTransformGestures { centroid, translation, factor, _ ->
                val old = zoom; val next = (old * factor).coerceIn(1f, 6f)
                val center = Offset(canvas.width / 2, canvas.height / 2)
                pan = (pan + center - centroid) * (next / old) + centroid - center + translation
                zoom = next
            }
        }.pointerInput(map.str("map_hash")) {
            detectTapGestures { touch ->
                if (updatedSelectable) {
                    val cell = (touch - origin()) / scale()
                    if (cell.x >= 0 && cell.y >= 0 && cell.x < width && cell.y < height &&
                        runs.any { it[3] == 1 && cell.y.toInt() == it[1] && cell.x >= it[0] && cell.x < it[0] + it[2] }) {
                        updatedTarget(Offset(left + (cell.x - .5f) * resolution, bottom + (height - .5f - cell.y) * resolution))
                    }
                }
            }
        }) {
        drawRect(Color(0xff182326))
        val sc = scale(); val start = origin()
        runs.forEach { r -> drawRect(if (r[3] == 1) Color(0xffd9e8e1) else Color(0xff64746e), start + Offset(r[0] * sc, r[1] * sc), Size(r[2] * sc + .1f, sc + .1f)) }
        fun pixel(x: Float, y: Float) = start + Offset(((x - left) / resolution + .5f) * sc, (height - .5f - (y - bottom) / resolution) * sc)
        target?.let { drawCircle(Color(0xffff8a21), 10.dp.toPx(), pixel(it.x, it.y)); drawCircle(Color.White, 3.dp.toPx(), pixel(it.x, it.y)) }
        val live = job?.takeIf { it.num("map_id") == map.num("map_id") && it.has("robot_x") && it.str("phase") !in setOf("done", "cancelled", "failed") }
        drawCircle(Color(0xff784dcc), 8.dp.toPx(), pixel((live ?: map).num("robot_x").toFloat(), (live ?: map).num("robot_y").toFloat()))
    }
}
