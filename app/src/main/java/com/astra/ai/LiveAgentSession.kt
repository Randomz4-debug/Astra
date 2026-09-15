package com.astra.ai

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Live Assist Mode: keeps Astra alongside the user's current app and reacts only to
 * explicitly permitted notification/screen context. It never intercepts third-party call audio.
 */
class LiveAgentSession(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var lastScreen = ""
    private var lastNotificationKey = ""

    companion object {
        @Volatile private var active: LiveAgentSession? = null
        fun current(): LiveAgentSession? = active
        fun isRunning(): Boolean = active != null
    }

    fun start() {
        stop()
        active = this
        app.getSharedPreferences("astra_runtime", Context.MODE_PRIVATE).edit().putBoolean("live_assist", true).apply()
        job = scope.launch {
            while (isActive) {
                inspectContext()
                delay(1500)
            }
        }
    }

    fun stop() {
        job?.cancel(); job = null
        if (active === this) active = null
        app.getSharedPreferences("astra_runtime", Context.MODE_PRIVATE).edit().putBoolean("live_assist", false).apply()
    }

    private suspend fun inspectContext() {
        val notification = AstraNotificationListenerService.latest()
        if (notification != null && notification.key != lastNotificationKey) {
            lastNotificationKey = notification.key
            // Context is available to the next user request; no automatic reply is sent.
        }
        val screen = AstraAccessibilityService.current()?.readScreen().orEmpty().trim().take(16000)
        if (screen.isNotBlank()) lastScreen = screen
    }

    fun contextSnapshot(): String = buildString {
        append("LIVE ASSIST MODE\n")
        append("Live assistance is active. Use only authorized Android context.\n")
        if (lastScreen.isNotBlank()) append("CURRENT SCREEN:\n").append(lastScreen).append('\n')
        AstraNotificationListenerService.latest()?.let { append("LATEST NOTIFICATION: ").append(it.title).append(" — ").append(it.text).append('\n') }
    }

    suspend fun assist(userRequest: String, localOnly: Boolean = false): String = withContext(Dispatchers.IO) {
        if (!isRunning()) start()
        val runtime = AstraAgentRuntime(app)
        runtime.handle(contextSnapshot() + "\nUSER REQUEST: " + userRequest, localOnly)
    }

    fun close() { stop(); scope.cancel() }
}
