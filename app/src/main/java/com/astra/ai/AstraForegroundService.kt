package com.astra.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class AstraForegroundService : Service() {
    companion object { const val ACTION_START = "ASTRA_START"; const val ACTION_STOP = "ASTRA_STOP" }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wake: AstraWakeWordController? = null
    private var runtime: AstraAgentRuntime? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel("astra_assistant", "Astra Assistant", NotificationManager.IMPORTANCE_LOW).apply { description = "User-enabled Astra background voice assistant" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val notification: Notification = NotificationCompat.Builder(this, "astra_assistant")
            .setContentTitle("Astra is active")
            .setContentText("Background voice assistant is enabled.")
            .setSmallIcon(com.astra.ai.R.drawable.ic_astra)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(1001, notification)
        runtime = AstraAgentRuntime(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        startAssistantLoop()
        return START_STICKY
    }

    private fun startAssistantLoop() {
        wake?.stop(); wake?.release()
        val controller = AstraWakeWordController(this)
        wake = controller
        val prefs = getSharedPreferences("astra_runtime", MODE_PRIVATE)
        val localOnly = prefs.getBoolean("local_only", false)
        val alwaysListen = prefs.getBoolean("always_listen", false)
        if (!alwaysListen) return
        val wakeWord = prefs.getString("wake_word", "astra") ?: "astra"
        controller.start("", "auto") { command ->
            if (command.isBlank()) return@start
            scope.launch {
                val response = runCatching { runtime?.handle(command, localOnly) ?: "Astra is not ready." }.getOrElse { "Astra error: ${it.message ?: "unknown error"}" }
                controller.speakResponse(response)
            }
        }
    }

    override fun onDestroy() {
        wake?.stop(); wake?.release(); wake = null
        scope.cancel(); runtime = null
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
