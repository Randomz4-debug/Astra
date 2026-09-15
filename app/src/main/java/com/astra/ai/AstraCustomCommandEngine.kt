package com.astra.ai

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class AstraCustomCommandEngine(private val context: Context) {
    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val active = ConcurrentHashMap<Int, Job>()
    }
    private val store = AstraCustomCommandStore(context)

    private fun norm(value: String): String = value.trim().lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").replace(Regex("\\s+"), " ").trim()
        .removePrefix("hey astra ").removePrefix("astra ").trim()

    suspend fun handle(input: String): ToolResult? {
        val text = input.trim(); if (text.isBlank()) return null
        val lower = norm(text)
        if (lower == "terminate" || lower == "terminate all custom commands" || lower == "stop all custom commands") {
            stopAll(); return ToolResult(true, "All custom commands terminated.")
        }
        if (lower.startsWith("stop custom command ") || lower.startsWith("terminate custom command ")) {
            val trigger = text.substringAfter("command ", "").trim()
            val c = store.list().firstOrNull { norm(it.trigger) == norm(trigger) || norm(it.trigger).contains(norm(trigger)) }
            if (c == null) return ToolResult(false, "Custom command not found: $trigger")
            stop(c.id); return ToolResult(true, "Custom command ${c.id} (${c.trigger}) stopped.")
        }
        if (lower.startsWith("delete custom command ")) {
            val trigger = text.substringAfter("command ", "").trim()
            val c = store.list().firstOrNull { norm(it.trigger) == norm(trigger) || norm(it.trigger).contains(norm(trigger)) }
            if (c == null) return ToolResult(false, "Custom command not found.")
            stop(c.id); store.delete(c.trigger); return ToolResult(true, "Deleted custom command ${c.trigger}.")
        }
        val off = lower.endsWith(" off")
        val candidate = text.removeSuffix(" on").removeSuffix(" off").trim()
        val command = store.list().firstOrNull { it.enabled && (norm(it.trigger) == norm(candidate) || norm(candidate) == norm(it.trigger).removePrefix("astra ")) }
            ?: return null
        return if (off) {
            stop(command.id); ToolResult(true, "Custom command ${command.id} (${command.trigger}) stopped.")
        } else {
            start(command); ToolResult(true, "Custom command ${command.id} (${command.trigger}) started.")
        }
    }

    private fun start(command: AstraCustomCommandStore.Command) { stop(command.id); active[command.id] = scope.launch { execute(command) } }

    private fun splitActions(raw: String): List<String> = raw.split(';').map { it.trim() }.filter { it.isNotBlank() }

    private fun arg(action: String): String = action.substringAfter(':', "").trim().let {
        if (it.length >= 2 && ((it.first() == '"' && it.last() == '"') || (it.first() == '\'' && it.last() == '\''))) it.substring(1, it.length - 1) else it
    }

    private suspend fun execute(command: AstraCustomCommandStore.Command) {
        try {
            val actions = splitActions(command.actions)
            for (action in actions) {
                if (!scope.coroutineContext.isActive || active[command.id]?.isActive == false) return
                val key = action.substringBefore(':').trim().lowercase().replace('-', '_').replace(' ', '_')
                val value = arg(action)
                val access = AstraAccessibilityService.current()
                when (key) {
                    "open_app", "app", "launch", "open" -> if (value.isNotBlank()) AppManager(context).open(value)
                    "open_url", "url", "website", "browser" -> if (value.isNotBlank()) DeviceTools(context).browser(value)
                    "maps", "open_maps", "navigate" -> if (value.isNotBlank()) DeviceTools(context).maps(value)
                    "camera", "take_photo", "photo" -> DeviceTools(context).camera()
                    "front_camera", "selfie" -> DeviceTools(context).camera(front = true)
                    "call", "dial" -> if (value.isNotBlank()) DeviceTools(context).call(value)
                    "click", "tap" -> if (value.isNotBlank()) access?.clickText(value)
                    "type", "text", "write" -> if (value.isNotBlank()) access?.typeText(value)
                    "back" -> access?.globalBack()
                    "home" -> access?.globalHome()
                    "recents" -> access?.globalRecents()
                    "notifications" -> access?.openNotifications()
                    "quick_settings", "quicksettings" -> access?.openQuickSettings()
                    "screenshot", "screen_shot" -> access?.takeSystemScreenshot()
                    "scroll", "scroll_down" -> access?.scrollForward()
                    "scroll_up" -> access?.scrollBackward()
                    "wait", "delay" -> delay(value.toLongOrNull()?.coerceIn(0L, 60000L) ?: 500L)
                    "open_settings", "settings" -> DeviceTools(context).settings()
                    "pick_file", "file" -> DeviceTools(context).filePicker()
                    "say", "speak" -> if (value.isNotBlank()) MultilingualVoiceController(context).speak(value)
                    "assist_chat", "chat_assist", "handle_chat" -> runChatAssistant(command.id)
                    "stop" -> return
                    "repeat" -> {
                        val parts = value.split(',', limit = 2)
                        val count = parts.getOrNull(0)?.trim()?.toIntOrNull()?.coerceIn(1, 20) ?: 1
                        val nested = parts.getOrNull(1).orEmpty().trim()
                        repeat(count) { if (nested.isNotBlank()) executeActions(command.id, splitActions(nested)) }
                    }
                }
                delay(50)
            }
        } finally {
            active.remove(command.id)
        }
    }

    private suspend fun executeActions(id: Int, actions: List<String>) {
        for (action in actions) {
            if (active[id]?.isActive == false) return
            val key = action.substringBefore(':').trim().lowercase().replace('-', '_').replace(' ', '_')
            val value = arg(action); val access = AstraAccessibilityService.current()
            when (key) {
                "open_app", "app", "launch", "open" -> if (value.isNotBlank()) AppManager(context).open(value)
                "click", "tap" -> if (value.isNotBlank()) access?.clickText(value)
                "type", "text", "write" -> if (value.isNotBlank()) access?.typeText(value)
                "wait", "delay" -> delay(value.toLongOrNull()?.coerceIn(0L, 60000L) ?: 500L)
                "back" -> access?.globalBack()
                "home" -> access?.globalHome()
                "scroll", "scroll_down" -> access?.scrollForward()
                "scroll_up" -> access?.scrollBackward()
                "open_url", "url", "website", "browser" -> if (value.isNotBlank()) DeviceTools(context).browser(value)
                "say", "speak" -> if (value.isNotBlank()) MultilingualVoiceController(context).speak(value)
            }
            delay(50)
        }
    }

    private suspend fun runChatAssistant(commandId: Int) {
        val access = AstraAccessibilityService.current() ?: return
        var previousScreen = ""
        while (scope.coroutineContext.isActive && active[commandId]?.isActive != false) {
            val screen = access.readScreenWithOcr().take(16000)
            if (screen.isNotBlank() && screen != previousScreen) {
                previousScreen = screen
                val instruction = "You are Astra's real-time chat assistant. Understand the visible app like a careful human. Read the current screen, distinguish incoming messages from UI labels, and reply only when there is a clear incoming message and a usable reply field. Preserve context and tone. Never invent what was said. Return ONLY the short reply, or exactly NO_REPLY if no response is appropriate. CURRENT SCREEN:\n$screen"
                val reply = AstraAgentRuntime(context).automationReason(instruction).trim()
                if (reply.isNotBlank() && !reply.equals("NO_REPLY", true)) {
                    if (access.typeText(reply)) { delay(150); if (!access.clickText("Send")) access.clickText("send") }
                }
            }
            delay(1600)
        }
    }

    fun stop(id: Int) { active.remove(id)?.cancel() }
    fun stopByTrigger(trigger: String) { store.list().firstOrNull { norm(it.trigger) == norm(trigger) }?.let { stop(it.id) } }
    fun stopAll() { active.values.toList().forEach { it.cancel() }; active.clear() }
    fun commands(): List<AstraCustomCommandStore.Command> = store.list()
    fun save(trigger: String, actions: String): AstraCustomCommandStore.Command = store.addOrUpdate(trigger, actions)
    fun delete(trigger: String): Boolean { val c = store.list().firstOrNull { norm(it.trigger) == norm(trigger) }; if (c != null) stop(c.id); return store.delete(trigger) }
    fun toggle(trigger: String, enabled: Boolean): Boolean = if (!enabled) { stopByTrigger(trigger); store.setEnabled(trigger, false) } else store.setEnabled(trigger, true)
}
