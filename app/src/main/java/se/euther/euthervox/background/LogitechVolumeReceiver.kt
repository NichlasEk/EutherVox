package se.euther.euthervox.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import java.util.UUID

/** Signature-protected local bridge. Requires an already authenticated Vox connection. */
class LogitechVolumeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val direction=intent.getStringExtra("direction")
        if(intent.action!="se.euther.euthervox.LOGITECH_VOLUME" || direction !in listOf("up","down")) {
            resultData="Ogiltigt volymkommando";return
        }
        val pending=goAsync()
        CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate).launch {
            try {
                val controller=EutherVoxNodeRuntime.controller(context)
                if(controller.state.value.remoteBusy) { pending.resultData="Ett kommando pågår. Vänta på svaret.";return@launch }
                controller.remoteRequest("logitech",JsonObject().apply {
                    addProperty("direction",direction);addProperty("request_id",UUID.randomUUID().toString())
                })
            } catch (_: Exception) {
                pending.resultData="Kontakten med EutherVox misslyckades. Inget automatiskt återförsök."
            } finally { pending.finish() }
        }
    }
}
