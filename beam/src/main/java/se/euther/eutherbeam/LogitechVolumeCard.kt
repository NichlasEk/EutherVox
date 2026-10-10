package se.euther.eutherbeam

import android.content.ComponentName
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun LogitechVolumeCard(enabled: Boolean=true, status: String?=null, onVolume: ((String, String)->Unit)?=null) {
    val context=LocalContext.current
    var lastTap by remember { mutableStateOf(emptyMap<String,Long>()) }
    var feedback by remember { mutableStateOf<String?>(null) }
    fun send(target:String, direction:String) {
        val now=android.os.SystemClock.elapsedRealtime()
        if(now-(lastTap[target] ?: -160L)<160)return
        lastTap=lastTap+(target to now)
        if(onVolume!=null) { onVolume(target,direction);return }
        feedback=null
        // New action prevents older Vox receivers from silently using the default node.
        val request=Intent("se.euther.euthervox.LOGITECH_VOLUME_TARGET")
            .setComponent(ComponentName("se.euther.euthervox","se.euther.euthervox.background.LogitechVolumeReceiver"))
            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            .putExtra("target_id",target).putExtra("direction",direction)
        try { context.sendBroadcast(request) }
        catch (_:SecurityException) { feedback="Uppdatera båda apparna från samma utgivare för Logitech-styrning." }
    }
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        listOf("nec" to "NEC · Bottenvåningen", "samsung" to "Samsung · Övervåningen").forEach { (target,label) ->
            Card(Modifier.fillMaxWidth(), colors=CardDefaults.cardColors(
                containerColor=MaterialTheme.colorScheme.surfaceContainer,
                contentColor=MaterialTheme.colorScheme.onSurface,
            )) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(label,style=MaterialTheme.typography.titleLarge)
                    Text("Logitech-ljud",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    listOf("up" to "Höj volymen","down" to "Sänk volymen").forEach { (direction,title) ->
                        Button(onClick={send(target,direction)},enabled=enabled,
                            modifier=Modifier.fillMaxWidth().heightIn(min=56.dp).semantics { contentDescription="$label: $title" }) {
                            Text((if(direction=="up")"＋  " else "−  ")+title,style=MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        }
        Text("Varje knapp styr bara sändaren för den TV:n. Rikta den mot Logitech.",style=MaterialTheme.typography.bodySmall)
        if(onVolume==null)Text("Via EutherVox – uppdatera och anslut den appen först.",style=MaterialTheme.typography.bodySmall)
        (status ?: feedback)?.takeIf { it.isNotBlank() }?.let { Text(it,color=MaterialTheme.colorScheme.error) }
    }
}
