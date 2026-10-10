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
        val target=if(intent.action=="se.euther.euthervox.LOGITECH_VOLUME_TARGET") intent.getStringExtra("target_id") else "nec"
        if(intent.action !in listOf("se.euther.euthervox.LOGITECH_VOLUME","se.euther.euthervox.LOGITECH_VOLUME_TARGET") || target !in listOf("nec","samsung") || direction !in listOf("up","down")) {
            resultData="Ogiltigt volymkommando";return
        }
        val pending=goAsync()
        CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate).launch {
            try {
                val controller=EutherVoxNodeRuntime.controller(context)
                if(controller.state.value.remoteBusy) { pending.resultData="Ett kommando pågår. Vänta på svaret.";return@launch }
                controller.remoteRequest("logitech",JsonObject().apply {
                    addProperty("target_id",target);addProperty("direction",direction);addProperty("request_id",UUID.randomUUID().toString())
                })
            } catch (_: Exception) {
                pending.resultData="Kontakten med EutherVox misslyckades. Inget automatiskt återförsök."
            } finally { pending.finish() }
        }
    }
}
