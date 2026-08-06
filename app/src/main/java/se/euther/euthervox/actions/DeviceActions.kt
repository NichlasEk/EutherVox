package se.euther.euthervox.actions

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import se.euther.euthervox.protocol.ServerEvent

data class DeviceActionResult(val status: String, val message: String)

interface DeviceActionExecutor {
    fun execute(action: ServerEvent.ActionRequest): DeviceActionResult
}

class AndroidDeviceActionExecutor(private val context: Context) : DeviceActionExecutor {
    override fun execute(action: ServerEvent.ActionRequest): DeviceActionResult {
        if (action.name != "media.play") {
            return DeviceActionResult("rejected", "Åtgärden ${action.name} är inte tillåten")
        }
        if (action.provider != YOUTUBE_MUSIC_PROVIDER) {
            return DeviceActionResult("rejected", "Okänd musiktjänst: ${action.provider}")
        }
        if (action.query.isBlank()) {
            return DeviceActionResult("failed", "Musiksökningen är tom")
        }

        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            setPackage(YOUTUBE_MUSIC_PACKAGE)
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            putExtra(SearchManager.QUERY, action.query)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            DeviceActionResult("completed", "Skickade '${action.query}' till YouTube Music")
        } catch (_: ActivityNotFoundException) {
            DeviceActionResult("failed", "YouTube Music är inte installerat eller kan inte spela från sökning")
        } catch (error: SecurityException) {
            DeviceActionResult("failed", "Android blockerade YouTube Music: ${error.message.orEmpty()}")
        }
    }

    private companion object {
        const val YOUTUBE_MUSIC_PROVIDER = "youtube_music"
        const val YOUTUBE_MUSIC_PACKAGE = "com.google.android.apps.youtube.music"
    }
}
