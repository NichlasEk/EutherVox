package se.euther.eutherbeam

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun LogitechVolumeCard(enabled: Boolean=true, status: String?=null, onVolume: ((String)->Unit)?=null) {
    val context=LocalContext.current
    var lastTap by remember { mutableStateOf(0L) }
    var feedback by remember { mutableStateOf<String?>(null) }
    fun send(direction:String) {
        val now=android.os.SystemClock.elapsedRealtime()
        if(now-lastTap<160)return
        lastTap=now
        if(onVolume!=null) { onVolume(direction);return }
        feedback=null
        val request=Intent("se.euther.euthervox.LOGITECH_VOLUME")
            .setComponent(ComponentName("se.euther.euthervox","se.euther.euthervox.background.LogitechVolumeReceiver"))
            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND).putExtra("direction",direction)
        try {
            context.sendBroadcast(request)
        } catch (_:SecurityException) {
            feedback="Uppdatera båda apparna från samma utgivare för Logitech-styrning."
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Logitech · IR-volym",style=MaterialTheme.typography.titleLarge)
            Button(onClick={send("up")},enabled=enabled,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) {
                Text("＋  Höj volymen",style=MaterialTheme.typography.titleMedium)
            }
            Button(onClick={send("down")},enabled=enabled,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) {
                Text("−  Sänk volymen",style=MaterialTheme.typography.titleMedium)
            }
            Text("Ett tryck = ett IR-kommando. Rikta sändaren mot Logitech.")
            if(onVolume==null)Text("Via EutherVox – anslut till servern där först.",style=MaterialTheme.typography.bodySmall)
            (status ?: feedback)?.takeIf { it.isNotBlank() }?.let { Text(it) }
        }
    }
}
