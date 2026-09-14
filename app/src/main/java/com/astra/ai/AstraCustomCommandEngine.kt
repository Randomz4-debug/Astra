package com.astra.ai

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Executes user-defined commands. Accessibility is used only after explicit user enablement. */
class AstraCustomCommandEngine(private val context: Context) {
    private val store = AstraCustomCommandStore(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val active = mutableMapOf<Int, Job>()

    suspend fun handle(input: String): ToolResult? {
        val text = input.trim(); if (text.isBlank()) return null
        val lower = text.lowercase()
        if (lower == "terminate" || lower == "terminate all custom commands" || lower == "stop all custom commands") { stopAll(); return ToolResult(true, "All custom commands terminated.") }
        if (lower.startsWith("stop custom command ") || lower.startsWith("terminate custom command ")) {
            val trigger = text.substringAfter("command ", "").trim(); val c = store.list().firstOrNull { it.trigger.equals(trigger, true) }
            if (c == null) return ToolResult(false, "Custom command not found: $trigger")
            stop(c.id); return ToolResult(true, "Custom command ${c.id} (${c.trigger}) stopped.")
        }
        if (lower.startsWith("delete custom command ")) { val trigger = text.substringAfter("command ", "").trim(); val ok = store.delete(trigger); stopByTrigger(trigger); return ToolResult(ok, if (ok) "Deleted custom command $trigger." else "Custom command not found.") }
        val off = lower.endsWith(" off"); val candidate = text.removeSuffix(" on").removeSuffix(" off").trim()
        val command = store.find(candidate) ?: return null
        return if (off) { stop(command.id); ToolResult(true, "Custom command ${command.id} (${command.trigger}) stopped.") } else { start(command); ToolResult(true, "Custom command ${command.id} (${command.trigger}) started.") }
    }

    private fun start(command: AstraCustomCommandStore.Command) { stop(command.id); active[command.id] = scope.launch { execute(command) } }

    private suspend fun execute(command: AstraCustomCommandStore.Command) {
        val actions = command.actions.split(';').map { it.trim() }.filter { it.isNotBlank() }
        for (action in actions) {
            if (!scope.coroutineContext.isActive) return
            val key = action.substringBefore(':').trim().lowercase(); val arg = action.substringAfter(':', "").trim(); val access = AstraAccessibilityService.current()
            when (key) {
                "open_app", "app", "launch" -> AppManager(context).open(arg)
                "open_url", "url", "website" -> DeviceTools(context).browser(arg)
                "camera", "take_photo", "photo" -> DeviceTools(context).camera()
                "call", "dial" -> DeviceTools(context).call(arg)
                "click", "tap" -> access?.clickText(arg)
                "type", "text" -> access?.typeText(arg)
                "back" -> access?.globalBack()
                "home" -> access?.globalHome()
                "recents" -> access?.globalRecents()
                "scroll" -> access?.scrollForward()
                "wait", "delay" -> delay(arg.toLongOrNull()?.coerceIn(0L, 60000L) ?: 500L)
                "assist_chat", "chat_assist", "handle_chat" -> runChatAssistant(command.id)
                "stop" -> return
            }
            delay(50)
        }
        if (actions.none { it.substringBefore(':').trim().equals("assist_chat", true) }) active.remove(command.id)
    }

    private suspend fun runChatAssistant(commandId: Int) {
        val access = AstraAccessibilityService.current() ?: return
        var previousScreen = ""
        while (scope.coroutineContext.isActive && active[commandId]?.isActive != false) {
            val screen = access.readScreen().take(12000)
            if (screen.isNotBlank() && screen != previousScreen) {
                previousScreen = screen
                val instruction = "You are Astra's authorized in-app chat assistant. Read the current app/game screen below. If there is a clear incoming chat message and a reply should be sent, return ONLY a short reply. If there is no clear message or no usable chat field, return exactly NO_REPLY. Screen:\n$screen"
                val reply = AstraAgentRuntime(context).automationReason(instruction).trim()
                if (reply.isNotBlank() && !reply.equals("NO_REPLY", true)) { access.typeText(reply); delay(150); if (!access.clickText("Send")) access.clickText("send") }
            }
            delay(1800)
        }
    }

    fun stop(id: Int) { active.remove(id)?.cancel() }
    fun stopByTrigger(trigger: String) { store.list().firstOrNull { it.trigger.equals(trigger, true) }?.let { stop(it.id) } }
    fun stopAll() { active.values.toList().forEach { it.cancel() }; active.clear() }
    fun commands(): List<AstraCustomCommandStore.Command> = store.list()
    fun save(trigger: String, actions: String): AstraCustomCommandStore.Command = store.addOrUpdate(trigger, actions)
    fun delete(trigger: String): Boolean { stopByTrigger(trigger); return store.delete(trigger) }
    fun toggle(trigger: String, enabled: Boolean): Boolean = if (!enabled) { stopByTrigger(trigger); store.setEnabled(trigger, false) } else store.setEnabled(trigger, true)
}
