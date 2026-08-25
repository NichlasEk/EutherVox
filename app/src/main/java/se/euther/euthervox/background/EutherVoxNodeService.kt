package se.euther.euthervox.background

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import se.euther.euthervox.MainActivity
import se.euther.euthervox.app.VoiceStatus

internal fun backgroundNodeNotificationText(
    status: VoiceStatus,
    canTalk: Boolean,
    connectionLabel: String,
): String = when (status) {
    VoiceStatus.Connecting -> "Återansluter till EutherVox…"
    VoiceStatus.Error -> "EutherVox väntar på nätverket"
    else -> if (canTalk) "EutherVox-noden är ansluten" else connectionLabel
}

class EutherVoxNodeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var notificationJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            preferences().edit(commit = true) { putBoolean(PREFERENCE_ENABLED, false) }
            EutherVoxNodeRuntime.controller(this).disconnect()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        startInForeground("Startar bakgrundsnoden…")
        EutherVoxNodeRuntime.connectSaved(this)
        observeConnection()
        return START_STICKY
    }

    override fun onDestroy() {
        notificationJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startInForeground(text: String) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(text), type)
    }

    private fun observeConnection() {
        if (notificationJob != null) return
        notificationJob = scope.launch {
            EutherVoxNodeRuntime.controller(this@EutherVoxNodeService).state.collectLatest { state ->
                val label = backgroundNodeNotificationText(
                    state.status,
                    state.canTalk,
                    state.connectionLabel,
                )
                if (
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(
                        this@EutherVoxNodeService,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) == PackageManager.PERMISSION_GRANTED
                ) runCatching {
                    NotificationManagerCompat.from(this@EutherVoxNodeService)
                        .notify(NOTIFICATION_ID, notification(label))
                }
            }
        }
    }

    private fun notification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(se.euther.euthervox.R.drawable.ic_launcher)
        .setContentTitle("EutherVox bakgrundsnod")
        .setContentText(text)
        .setContentIntent(PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ))
        .addAction(
            0,
            "Koppla från",
            PendingIntent.getService(
                this,
                1,
                Intent(this, EutherVoxNodeService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .build()

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "EutherVox bakgrundsnod",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Håller EutherVox ansluten för lokala husmeddelanden och TTS"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun preferences() = getSharedPreferences("euthervox", Context.MODE_PRIVATE)

    companion object {
        const val PREFERENCE_ENABLED = "background_node_enabled"
        private const val ACTION_STOP = "se.euther.euthervox.action.STOP_BACKGROUND_NODE"
        private const val CHANNEL_ID = "euthervox_background_node"
        private const val NOTIFICATION_ID = 3020

        fun start(context: Context) {
            context.getSharedPreferences("euthervox", Context.MODE_PRIVATE)
                .edit(commit = true) { putBoolean(PREFERENCE_ENABLED, true) }
            ContextCompat.startForegroundService(
                context,
                Intent(context, EutherVoxNodeService::class.java),
            )
        }

        fun stopKeepingAlive(context: Context) {
            context.getSharedPreferences("euthervox", Context.MODE_PRIVATE)
                .edit(commit = true) { putBoolean(PREFERENCE_ENABLED, false) }
            context.stopService(Intent(context, EutherVoxNodeService::class.java))
        }
    }
}
