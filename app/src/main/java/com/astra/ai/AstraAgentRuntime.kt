package com.astra.ai

import android.content.Context
import android.content.Intent

/** Shared assistant brain used by native UI, Web UI and background services. */
class AstraAgentRuntime(context: Context) {
    private val appContext = context.applicationContext
    private val localEngine: AiEngine = LocalAiEngine(appContext)
    private val cloudEngine: AiEngine = OpenAiResponsesEngine(appContext)
    private val commands = LocalCommandEngine(appContext)
    private val planner = AstraCommandPlanner(appContext)
    private val customCommands = AstraCustomCommandEngine(appContext)
    private val memory = MemoryManager(appContext)
    private val connectivity = AstraConnectivityManager(appContext)
    private val prefs = appContext.getSharedPreferences("astra_runtime", Context.MODE_PRIVATE)
    private val providers = AiProviderRegistry(appContext)
    private val aiConfig = AstraAIConfigurationStore(appContext)
    private val providerRouter = AstraAIProviderRouter(appContext)
    private val chats = AstraChatStore(appContext)
    private val workspace = AstraWorkspace(appContext)
    private val taskManager = AstraTaskManager(appContext)
    private val apiHub = AstraApiHub(appContext)
    private val connectionTools = AstraConnectionTools(appContext)

    suspend fun handle(input: String, localOnly: Boolean): String {
        val clean = input.trim(); if (clean.isBlank()) return ""; val lower = clean.lowercase()
        if (lower == "api manager" || lower == "api settings" || lower == "manage apis" || lower == "open api manager" || lower == "open api settings") {
            return runCatching { appContext.startActivity(Intent(appContext, AstraApiActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); "Opening the Astra API Hub." }.getOrElse { "Could not open the API Hub: ${it.message}" }
        }
        if (lower in setOf("what apps are connected", "show connected apps", "list connected apps", "connected apps")) {
            val apps = connectionTools.getConnectedApps()
            return if (apps.isBlank()) "No apps are connected to Astra." else "Connected apps: $apps"
        }
        if (lower == "connect app" || lower == "connect apps" || lower == "open connected apps") {
            return runCatching { appContext.startActivity(Intent(appContext, AstraConnectedAppsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); "Opening Connected Apps." }.getOrElse { "Could not open Connected Apps: ${it.message}" }
        }
        if (lower.startsWith("open app ")) return connectionTools.openApp(clean.substringAfter("open app ").trim())
        if (lower.startsWith("disconnect ")) return connectionTools.disconnect(clean.substringAfter("disconnect ").trim())

        val customResult = runCatching { customCommands.handle(clean) }.getOrNull(); if (customResult != null) return customResult.message
        if (lower.startsWith("run in background ") || lower.startsWith("start background task ") || lower.startsWith("run task in background ")) {
            val prompt = clean.substringAfter("background", "").trim().removePrefix("task").trim()
            if (prompt.isBlank()) return "Tell me what you want me to run in the background."
            val id = taskManager.start(prompt); return "Background task $id started. I will execute its steps in order."
        }
        if (lower == "list background tasks" || lower == "show background tasks" || lower == "tasks") {
            val all = taskManager.all(); return if (all.isEmpty()) "No background tasks." else all.joinToString("; ") { "${it.id}: ${it.status} ${it.step}/${it.total}" }
        }
        if (lower.startsWith("stop background task ") || lower.startsWith("terminate background task ")) {
            val id = clean.substringAfterLast(' ').trim(); return if (taskManager.stop(id)) "Stopped background task $id." else "Background task $id was not running."
        }

        val chatId = currentChatId()
        chats.append(chatId, "user", clean)

        // Execute compound requests before the simple command router. This is the key fix for
        // requests such as "open WhatsApp and read the last message...": opening the app is only
        // step one, not the whole answer.
        val planned = runCatching { planner.plan(clean) }.getOrNull()
        if (planned?.handled == true) {
            val finalAnswer = when {
                !planned.toolFailure.isNullOrBlank() -> "I stopped because the required action failed: ${planned.toolFailure}"
                !planned.reasoningRequest.isNullOrBlank() -> runCatching { generateModelAnswer(chatId, planned.reasoningRequest!!, localOnly) }
                    .getOrElse { "I completed the available screen actions, but could not safely interpret the result: ${it.message ?: "unknown error"}" }
                else -> "Done."
            }
            chats.append(chatId, "assistant", finalAnswer)
            return finalAnswer
        }

        val answer = try {
            when {
                lower == "stop" || lower == "cancel" || lower == "astra stop" -> { customCommands.stopAll(); taskManager.stopAll(); "Stopped." }
                lower.startsWith("call ") || lower.startsWith("dial ") -> { val target = clean.substringAfter(' ').trim(); commands.handle("call $target")?.message ?: "I could not start the call." }
                lower.startsWith("rename yourself to ") || lower.startsWith("call yourself ") -> { val name = clean.substringAfter(" to ", "").trim().ifBlank { clean.substringAfter(' ').trim() }; if (name.isNotBlank()) { prefs.edit().putString("assistant_name", name).apply(); "Okay. From now on, I'm $name." } else "Please tell me the new name you want me to use." }
                lower.startsWith("switch to offline") || lower == "use offline ai" || lower == "go offline" -> { aiConfig.setMode("offline"); "Switched to offline/local AI." }
                lower.startsWith("switch to online") || lower == "use online ai" || lower == "go online" -> { aiConfig.setMode("online"); "Switched to online AI when internet is available." }
                lower == "automatic mode" || lower == "auto ai" || lower == "use automatic ai" -> { aiConfig.setMode("auto"); "Automatic AI routing enabled." }
                lower.startsWith("use model ") -> { val model = clean.substringAfter("use model ").trim(); val gateway = LocalAiGateway(appContext); gateway.configure(gateway.endpoint(), model); "Local model set to $model." }
                lower.startsWith("use provider ") -> { val id = clean.substringAfter("use provider ").trim(); prefs.edit().putString("selected_provider", id).apply(); "Provider selection saved. If available, I'll use that provider." }
                lower.startsWith("remember that ") -> { val body = clean.substringAfter("remember that "); val parts = body.split(" is ", limit = 2); if (parts.size == 2) { memory.remember(parts[0].trim(), parts[1].trim()); "I'll remember that locally." } else "Tell me what you want me to remember." }
                lower == "what do you remember" || lower == "show my memory" -> { val all = memory.all(); if (all.isEmpty()) "I don't have any saved local memories." else all.entries.joinToString("; ") { "${it.key}: ${it.value}" } }
                lower.startsWith("forget ") -> { memory.forget(clean.substringAfter("forget ").trim()); "Forgotten from local Astra memory." }
                lower == "delete all astra memory" -> "I need confirmation before deleting all Astra memory."
                lower.startsWith("add custom command ") -> { val body = clean.substringAfter("add custom command ").trim(); val parts = body.split("=", limit = 2); if (parts.size != 2) "Use: add custom command <trigger> = <actions>" else runCatching { val c = customCommands.save(parts[0].trim(), parts[1].trim()); "Saved custom command ${c.id}: ${c.trigger}." }.getOrElse { "Could not save command: ${it.message}" } }
                lower.startsWith("delete custom command ") -> { val trigger = clean.substringAfter("command ").trim(); if (customCommands.delete(trigger)) "Deleted custom command $trigger." else "Custom command not found." }
                lower == "list custom commands" || lower == "show custom commands" -> { val all = customCommands.commands(); if (all.isEmpty()) "No custom commands saved." else all.joinToString("; ") { "${it.id}: ${it.trigger} -> ${it.actions}" } }
                else -> { val tool = commands.handle(clean); if (tool != null) tool.message else generateModelAnswer(chatId, clean, localOnly) }
            }
        } catch (t: Throwable) { "Astra error: ${t.message ?: "unknown error"}" }

        var finalAnswer = answer
        if (isCannedGreeting(answer) && !isGreeting(clean)) {
            val retry = runCatching {
                generateModelAnswer(chatId, "Answer the user's request directly. Do not greet, introduce yourself, or say you are here to help. User request: $clean", localOnly)
            }.getOrNull().orEmpty()
            finalAnswer = if (retry.isNotBlank() && !isCannedGreeting(retry)) retry
            else "I received your message, but Astra's configured AI model did not return a useful answer. Check the AI connection/model in Astra Settings."
        }
        chats.append(chatId, "assistant", finalAnswer); return finalAnswer
    }

    private fun isGreeting(text: String): Boolean {
        val n = text.lowercase().trim().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ")
        return n in setOf("hi", "hello", "hey", "hey astra", "hi astra", "hello astra", "good morning", "good afternoon", "good evening")
    }

    private fun isCannedGreeting(text: String): Boolean {
        val n = text.lowercase().trim().replace(Regex("\\s+"), " ")
        return n == "hi i am astra and i am here to help you." ||
            n == "hi, i am astra and i am here to help you." ||
            n == "hi i am astra and i'm here to help you." ||
            (n.length < 100 && n.contains("i am astra") && n.contains("here to help"))
    }

    suspend fun automationReason(instruction: String): String = generateModelAnswer(currentChatId(), instruction, false)

    private suspend fun generateModelAnswer(chatId: String, clean: String, localOnly: Boolean): String {
        val history = chats.recentMessages(chatId, oneYear = true, limit = 80)
        val historyText = if (history.isEmpty()) "(no earlier messages)" else history.dropLast(1).joinToString("\n") { "${if (it.role == "user") "USER" else "ASTRA"}: ${it.text}" }
        val workspaceText = workspace.contextText()
        val screenText = AstraAccessibilityService.current()?.readScreen().orEmpty().trim().take(16000)
        val apiCatalog = apiHub.catalog()
        val config = aiConfig.read()
        val decision = providerRouter.decide()
        val toolCapabilities = """
You are the reasoning brain inside the Android assistant Astra. The Android execution layer is part of the same assistant and the same persisted chat/memory/settings state is used by the foreground app, background voice service and VoiceInteractionSession.
Available capabilities include authorized app launching, opening URLs and Maps, camera/photo workflows, screen understanding and interaction when Accessibility Access is enabled, user-authorized screen capture, notifications/replies where Android exposes an action, phone calls where permitted, files/workspace, memory, local/LAN/cloud AI, Connected Apps and user-configured REST APIs.
CUSTOM API ACCESS: Astra can call any number of user-configured REST APIs. API definitions are listed below. When a user asks to use one by name, the execution layer may perform the request. Do not invent API results.
SCREEN ACCESS: CURRENT SCREEN TEXT below is live accessibility information from the active window when available. Use it as evidence, not as a guess. For interaction requests, the execution layer handles the action when Accessibility Access is enabled.
CONNECTED APPS: A connection record means Astra has a legitimate connection/capability; opening an installed app is not the same thing as authenticating an account. Never claim an account is connected unless Astra's connection manager says it is connected.
COMMON-SENSE EXECUTION: Infer ordinary intent from context, resolve pronouns and references from recent messages and visible screen text, understand slang/typos/mixed-language phrasing, and make a short plan for multi-step requests. Do not stop after the first successful step. Do not report an app-open action as the answer when the user asked for information from that app.
MESSAGE READING: When reading a chat, distinguish sender, message content, timestamps, buttons, headers and unrelated UI text. Never invent a message. If the requested message is not visible or the sender cannot be verified, say so.
TRUTHFULNESS: Never claim an action succeeded unless the execution layer reported success. Never claim you logged in, connected an account, read a private message, sent a message, or completed a UI action when Astra did not actually do it.
PRIVACY: Use only explicitly authorized screen/accessibility/notification/camera capabilities. Do not expose unrelated private content.
AI ROUTING: The selected Astra mode/provider policy below is authoritative and is shared by foreground/background/lock-screen clients. Current decision: ${decision.provider.name} (${decision.reason}). Configuration mode=${config.mode}, onlineProvider=${config.onlineProvider}, offlineProvider=${config.offlineProvider}, cloudProcessingAllowed=${config.cloudProcessingAllowed}.
""".trimIndent()
        val prompt = AstraPersona.systemPrompt(appContext) + "\n\n" + toolCapabilities + "\n\nCONFIGURED REST APIS:\n" + apiCatalog + "\n\nCURRENT SCREEN TEXT:\n" + if (screenText.isBlank()) "(unavailable; Accessibility Access may be disabled)" else screenText + "\n\nRECENT ASTRA CHAT HISTORY (up to 1 year, current chat):\n" + historyText + "\n\nIMPORTED ASTRA WORKSPACE:\n" + workspaceText + "\n\nCURRENT USER REQUEST:\n" + clean
        if (localOnly || decision.provider == AstraAIProviderRouter.Provider.OFFLINE) return localEngine.respond(prompt)
        return cloudOrLocal(prompt)
    }

    fun currentChatId(): String { val existing = prefs.getString("current_chat_id", null); if (!existing.isNullOrBlank()) return existing; val chat = chats.ensureChat(title = "New chat"); prefs.edit().putString("current_chat_id", chat.id).apply(); return chat.id }
    fun newChat(): String { val chat = chats.ensureChat(title = "New chat"); prefs.edit().putString("current_chat_id", chat.id).apply(); return chat.id }
    fun selectChat(id: String): Boolean { val exists = chats.listChats().any { it.id == id }; if (!exists) return false; prefs.edit().putString("current_chat_id", id).apply(); return true }
    fun listChats(): List<AstraChatStore.Chat> = chats.listChats()
    fun messages(chatId: String): List<AstraChatStore.Message> = chats.messages(chatId, 0L, 500)
    fun renameChat(id: String, title: String) = chats.rename(id, title)
    fun deleteChat(id: String) { chats.delete(id); if (prefs.getString("current_chat_id", "") == id) prefs.edit().remove("current_chat_id").apply() }
    fun clearAllChats() { chats.clearAll(); prefs.edit().remove("current_chat_id").apply() }
    fun customCommands(): List<AstraCustomCommandStore.Command> = customCommands.commands()
    fun stopCustomCommands() = customCommands.stopAll()

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
