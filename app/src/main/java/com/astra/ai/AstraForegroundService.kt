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
    companion object {
        const val ACTION_START = "ASTRA_START"
        const val ACTION_STOP = "ASTRA_STOP"
        const val ACTION_START_PROJECTION = "ASTRA_START_PROJECTION"
        const val ACTION_START_LIVE_ASSIST = "ASTRA_START_LIVE_ASSIST"
        const val ACTION_STOP_LIVE_ASSIST = "ASTRA_STOP_LIVE_ASSIST"
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wake: AstraWakeWordController? = null
    private var runtime: AstraAgentRuntime? = null
    private var live: LiveAgentSession? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel("astra_assistant", "Astra Assistant", NotificationManager.IMPORTANCE_LOW).apply { description = "User-enabled Astra background services" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        runtime = AstraAgentRuntime(this)
        promote(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }
    private fun promote(type: Int) {
        val text = if (live != null) "Live Assist is active. Tap Astra to stop." else if (type and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION != 0) "Screen access is enabled by your choice." else "Background voice assistant is enabled."
        val notification: Notification = NotificationCompat.Builder(this, "astra_assistant").setContentTitle("Astra is active").setContentText(text).setSmallIcon(com.astra.ai.R.drawable.ic_astra).setOngoing(true).setCategory(Notification.CATEGORY_SERVICE).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1001, notification, type) else startForeground(1001, notification)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { live?.close(); live=null; stopSelf(); return START_NOT_STICKY }
            ACTION_START_PROJECTION -> { runCatching { promote(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) }; return START_STICKY }
            ACTION_START_LIVE_ASSIST -> { live?.close(); live=LiveAgentSession(this).also{it.start()}; promote(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE); return START_STICKY }
            ACTION_STOP_LIVE_ASSIST -> { live?.close(); live=null; if (!getSharedPreferences("astra_runtime",0).getBoolean("always_listen",false)) stopSelf(); return START_NOT_STICKY }
            else -> startAssistantLoop()
        }
        return START_STICKY
    }
    private fun startAssistantLoop() {
        wake?.stop(); wake?.release(); val controller=AstraWakeWordController(this); wake=controller
        val prefs=getSharedPreferences("astra_runtime",MODE_PRIVATE); if(!prefs.getBoolean("always_listen",false))return
        controller.start("","auto"){command->if(command.isBlank())return@start;scope.launch{val response=runCatching{runtime?.handle(command,prefs.getBoolean("local_only",false))?:"Astra is not ready."}.getOrElse{"Astra error: ${it.message?:"unknown error"}"};controller.speakResponse(response)}}
    }
    override fun onDestroy(){wake?.stop();wake?.release();wake=null;live?.close();live=null;scope.cancel();runtime=null;super.onDestroy()}
    override fun onBind(intent:Intent?):IBinder?=null
}
