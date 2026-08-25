package se.euther.euthervox.background

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import se.euther.euthervox.app.VoiceController

data class NodeConnectionSettings(
    val serverAddress: String,
    val nodeName: String,
    val username: String,
    val voiceId: String,
    val characterId: String,
    val llmModel: String,
) {
    val isConfigured: Boolean get() = serverAddress.isNotBlank()

    companion object {
        fun load(context: Context): NodeConnectionSettings {
            val preferences = context.getSharedPreferences("euthervox", Context.MODE_PRIVATE)
            return NodeConnectionSettings(
                serverAddress = preferences.getString("server_address", "").orEmpty(),
                nodeName = preferences.getString("node_name", "android-phone").orEmpty(),
                username = preferences.getString("username", "").orEmpty(),
                voiceId = preferences.getString("voice_id", "piper-nst").orEmpty(),
                characterId = preferences.getString("character_id", "skinnskattaren").orEmpty(),
                llmModel = preferences.getString("llm_model", "qwen3:4b-instruct").orEmpty(),
            )
        }
    }
}

object EutherVoxNodeRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var sharedController: VoiceController? = null

    fun controller(context: Context): VoiceController =
        sharedController ?: synchronized(this) {
            sharedController ?: VoiceController(context.applicationContext, scope).also {
                sharedController = it
            }
        }

    fun connectSaved(context: Context) {
        val settings = NodeConnectionSettings.load(context)
        if (!settings.isConfigured) return
        controller(context).connect(
            settings.serverAddress,
            settings.nodeName,
            settings.username,
            requestedVoiceId = settings.voiceId,
            requestedCharacterId = settings.characterId,
            requestedLlmModel = settings.llmModel,
        )
    }
}
