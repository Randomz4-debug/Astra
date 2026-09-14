package com.astra.ai

import android.content.Context

/** Shared assistant brain used by both the foreground UI and the background voice service. */
class AstraAgentRuntime(context: Context) {
    private val appContext = context.applicationContext
    private val localEngine: AiEngine = LocalAiEngine()
    private val cloudEngine: AiEngine = OpenAiResponsesEngine(appContext)
    private val commands = LocalCommandEngine(appContext)
    private val memory = MemoryManager(appContext)

    suspend fun handle(input: String, localOnly: Boolean): String {
        val clean = input.trim()
        if (clean.isBlank()) return ""
        val lower = clean.lowercase()

        if (lower == "stop" || lower == "cancel" || lower == "astra stop") return "Stopped."

        if (lower.startsWith("remember that ")) {
            val body = clean.substringAfter("remember that ")
            val parts = body.split(" is ", limit = 2)
            if (parts.size == 2) {
                memory.remember(parts[0].trim(), parts[1].trim())
                return "I'll remember that locally."
            }
        }

        if (lower == "what do you remember" || lower == "show my memory") {
            val all = memory.all()
            return if (all.isEmpty()) "I don't have any saved local memories."
            else all.entries.joinToString("; ") { "${it.key}: ${it.value}" }
        }

        if (lower.startsWith("forget ")) {
            memory.forget(clean.substringAfter("forget ").trim())
            return "Forgotten from local Astra memory."
        }

        if (lower == "delete all astra memory") {
            return "I need confirmation before deleting all Astra memory."
        }

        commands.handle(clean)?.let { return it.message }
        return if (localOnly) localEngine.respond(clean) else cloudEngine.respond(clean)
    }
}
