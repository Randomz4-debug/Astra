package com.astra.ai

import android.content.Context

/** Shared assistant brain used by native UI, Web UI and background services. */
class AstraAgentRuntime(context: Context) {
    private val appContext = context.applicationContext
    private val localEngine: AiEngine = LocalAiEngine(appContext)
    private val cloudEngine: AiEngine = OpenAiResponsesEngine(appContext)
    private val commands = LocalCommandEngine(appContext)
    private val memory = MemoryManager(appContext)
    private val connectivity = AstraConnectivityManager(appContext)
    private val prefs = appContext.getSharedPreferences("astra_runtime", Context.MODE_PRIVATE)
    private val providers = AiProviderRegistry(appContext)

    suspend fun handle(input: String, localOnly: Boolean): String {
        val clean = input.trim(); if (clean.isBlank()) return ""
        val lower = clean.lowercase()
        if (lower == "stop" || lower == "cancel" || lower == "astra stop") return "Stopped."

        if (lower.startsWith("call ") || lower.startsWith("dial ")) {
            val target = clean.substringAfter(' ').trim()
            return commands.handle("call $target")?.message ?: "I could not start the call."
        }
        if (lower.startsWith("rename yourself to ") || lower.startsWith("call yourself ")) {
            val name = clean.substringAfter(" to ", "").trim().ifBlank { clean.substringAfter(' ').trim() }
            if (name.isNotBlank()) { prefs.edit().putString("assistant_name", name).apply(); return "Okay. From now on, I'm $name." }
        }

        when {
            lower == "switch to offline" || lower == "use offline ai" || lower == "go offline" -> { prefs.edit().putString("ai_mode", "offline").apply(); return "Switched to offline/local AI." }
            lower == "switch to online" || lower == "use online ai" || lower == "go online" -> { prefs.edit().putString("ai_mode", "online").apply(); return "Switched to online AI when internet is available." }
            lower == "automatic mode" || lower == "auto ai" || lower == "use automatic ai" -> { prefs.edit().putString("ai_mode", "auto").apply(); return "Automatic AI routing enabled." }
            lower.startsWith("use model ") -> { val model = clean.substringAfter("use model ").trim(); val gateway = LocalAiGateway(appContext); gateway.configure(gateway.endpoint(), model); return "Local model set to $model." }
            lower.startsWith("use provider ") -> { val id = clean.substringAfter("use provider ").trim(); prefs.edit().putString("selected_provider", id).apply(); return "Provider selection saved. If available, I'll use that provider." }
        }

        if (lower.startsWith("remember that ")) {
            val body = clean.substringAfter("remember that "); val parts = body.split(" is ", limit = 2)
            if (parts.size == 2) { memory.remember(parts[0].trim(), parts[1].trim()); return "I'll remember that locally." }
        }
        if (lower == "what do you remember" || lower == "show my memory") {
            val all = memory.all(); return if (all.isEmpty()) "I don't have any saved local memories." else all.entries.joinToString("; ") { "${it.key}: ${it.value}" }
        }
        if (lower.startsWith("forget ")) { memory.forget(clean.substringAfter("forget ").trim()); return "Forgotten from local Astra memory." }
        if (lower == "delete all astra memory") return "I need confirmation before deleting all Astra memory."

        // Tool commands always run before the reasoning model, regardless of online/offline mode.
        commands.handle(clean)?.let { return it.message }

        val prompt = AstraPersona.systemPrompt(appContext) + "\n\nUSER REQUEST:\n" + clean
        val mode = prefs.getString("ai_mode", "auto") ?: "auto"
        if (localOnly || mode == "offline") return localEngine.respond(prompt)
        if (mode == "online") return cloudOrLocal(prompt)
        return if (connectivity.hasInternet()) cloudOrLocal(prompt) else localEngine.respond(prompt)
    }

    private suspend fun cloudOrLocal(prompt: String): String {
        val selected = prefs.getString("selected_provider", "")?.trim().orEmpty()
        if (selected.isNotBlank()) {
            val answer = runCatching { providers.chat(selected, prompt) }.getOrNull()
            if (!answer.isNullOrBlank() && !answer.startsWith("Provider unavailable")) return answer
        }
        val cloud = cloudEngine.respond(prompt)
        if (cloud == "OpenAI is unavailable right now." || cloud.startsWith("OpenAI request failed") || cloud == "OpenAI is not configured. Add your API key in Astra's cloud settings.") return localEngine.respond(prompt)
        return cloud
    }
}
