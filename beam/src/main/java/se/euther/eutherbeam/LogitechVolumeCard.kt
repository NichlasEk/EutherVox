package se.euther.eutherbeam

import android.app.Activity
import android.content.BroadcastReceiver
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
    var pending by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    fun send(direction:String) {
        if(onVolume!=null) { onVolume(direction);return }
        pending=true;feedback="Skickar via EutherVox…"
        val request=Intent("se.euther.euthervox.LOGITECH_VOLUME")
            .setComponent(ComponentName("se.euther.euthervox","se.euther.euthervox.background.LogitechVolumeReceiver"))
            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND).putExtra("direction",direction)
        try {
            context.sendOrderedBroadcast(request,null,object:BroadcastReceiver(){
                override fun onReceive(context:Context,intent:Intent) {
                    pending=false
                    feedback=resultData ?: "Installera eller uppdatera EutherVox och anslut där först."
                }
            },null,Activity.RESULT_CANCELED,null,null)
        } catch (_:SecurityException) {
            pending=false;feedback="Uppdatera båda apparna från samma utgivare för Logitech-styrning."
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Logitech · IR-volym",style=MaterialTheme.typography.titleLarge)
            Button(onClick={send("up")},enabled=enabled && !pending,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) {
                Text("＋  Höj volymen",style=MaterialTheme.typography.titleMedium)
            }
            Button(onClick={send("down")},enabled=enabled && !pending,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) {
                Text("−  Sänk volymen",style=MaterialTheme.typography.titleMedium)
            }
            Text("Ett tryck = ett IR-kommando. Rikta sändaren mot Logitech.")
            if(onVolume==null)Text("Via EutherVox – anslut till servern där först.",style=MaterialTheme.typography.bodySmall)
            (status ?: feedback)?.let { Text(it) }
        }
    }
}
