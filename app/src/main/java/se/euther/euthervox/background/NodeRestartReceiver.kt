package se.euther.euthervox.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class NodeRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        EutherVoxNodeService.migrateBackgroundMessages(context)
        val preferences = context.getSharedPreferences("euthervox", Context.MODE_PRIVATE)
        if (preferences.getBoolean(EutherVoxNodeService.PREFERENCE_ENABLED, true) &&
            NodeConnectionSettings.load(context).isConfigured) {
            EutherVoxNodeService.start(context)
        }
    }
}
