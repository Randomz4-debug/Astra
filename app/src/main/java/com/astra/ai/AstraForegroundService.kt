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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var wake: AstraWakeWordController
    private lateinit var runtime: AstraAgentRuntime

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel("astra_assistant", "Astra Assistant", NotificationManager.IMPORTANCE_LOW).apply { description = "User-enabled Astra background voice assistant" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val notification: Notification = NotificationCompat.Builder(this, "astra_assistant")
            .setContentTitle("Astra is active")
            .setContentText("Always-listening mode is enabled by the user.")
            .setSmallIcon(com.astra.ai.R.drawable.ic_astra)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(1001, notification)

        runtime = AstraAgentRuntime(this)
        wake = AstraWakeWordController(this)
        val prefs = getSharedPreferences("astra_runtime", MODE_PRIVATE)
        val localOnly = prefs.getBoolean("local_only", false)
        val alwaysListen = prefs.getBoolean("always_listen", false)
        val wakeWord = prefs.getString("wake_word", "astra") ?: "astra"
        wake.start(if (alwaysListen) "" else wakeWord, "auto") { command ->
            if (command.isBlank()) return@start
            scope.launch {
                val response = runCatching { runtime.handle(command, localOnly) }.getOrElse { "Astra error: ${it.message ?: "unknown error"}" }
                wake.speakResponse(response)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onDestroy() { wake.stop(); wake.release(); scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
