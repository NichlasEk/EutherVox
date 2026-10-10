package se.euther.euthervox.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
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
                val id=UUID.randomUUID().toString()
                controller.remoteRequest("logitech",JsonObject().apply { addProperty("direction",direction);addProperty("request_id",id) })
                val state=withTimeoutOrNull(8000) { controller.state.first { !it.remoteBusy } }
                val data=state?.remoteData
                pending.resultData=when {
                    state==null -> "Svar saknas. Kontrollera ljudet innan du trycker igen; inget skickas om automatiskt."
                    data?.get("request_id")?.asString==id -> data.get("message")?.asString ?: "Kontrollera ljudsystemets svar."
                    else -> data?.get("message")?.asString ?: "Öppna EutherVox och anslut till servern först."
                }
            } catch (_: Exception) {
                pending.resultData="Kontakten med EutherVox misslyckades. Inget automatiskt återförsök."
            } finally { pending.finish() }
        }
    }
}
