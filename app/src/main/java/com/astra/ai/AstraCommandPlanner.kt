package com.astra.ai

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Deterministic planner for multi-step natural-language commands. */
class AstraCommandPlanner(context: Context) {
    data class Result(val handled: Boolean, val toolFailure: String? = null, val reasoningRequest: String? = null)
    private val app = context.applicationContext
    private val commands = LocalCommandEngine(app)

    suspend fun plan(input: String): Result? = withContext(Dispatchers.IO) {
        val clean = input.trim()
        if (clean.isBlank()) return@withContext null

        // A play request is a workflow: launch -> wait for the game -> inspect -> press
        // an actually visible Start/Play/Continue/Begin control. It must never fall back
        // to Android Settings just because the game is still loading.
        val playGame = Regex("(?is)^(?:please\\s+|could\\s+you\\s+|can\\s+you\\s+|hey\\s+astra\\s+|astra\\s+)*(?:play|start playing)\\s+(.+?)\\s*$").find(clean)
        if (playGame != null) {
            val requestedGame = playGame.groupValues[1].trim().removePrefix("the ").trim()
            if (requestedGame.isNotBlank() && requestedGame.lowercase() !in setOf("game", "the game")) {
                return@withContext playGameWorkflow(clean, requestedGame)
            }
        }

        val readChat = Regex("(?is)^open\\s+(.+?)\\s+and\\s+(?:read|tell me)\\s+(?:the\\s+)?(?:last|latest)\\s+message\\s+(?:sent\\s+by|from)\\s+(.+?)\\s*$").find(clean)
        if (readChat != null) return@withContext planReadChat(clean, readChat.groupValues[1].trim(), readChat.groupValues[2].trim())

        val steps = splitSteps(clean)
        if (steps.size < 2) return@withContext null
        var executed = 0
        var lastFailure: String? = null
        var remainder: String? = null
        for ((index, step) in steps.withIndex()) {
            val tool = runCatching { commands.handle(step) }.getOrNull()
            if (tool != null) {
                if (!tool.ok) { lastFailure = tool.message; break }
                executed++
                if (isAppOpenStep(step)) { prepareOpenedApp(step, clean); delay(4500) }
                else if (isNavigationStep(step)) delay(650)
            } else { remainder = steps.drop(index).joinToString(" and "); break }
        }
        if (executed == 0) return@withContext null
        if (lastFailure != null) return@withContext Result(true, toolFailure = lastFailure)
        if (remainder.isNullOrBlank()) return@withContext Result(true)
        Result(true, reasoningRequest = buildReasoningRequest(clean, remainder!!))
    }

    private suspend fun playGameWorkflow(original: String, gameName: String): Result {
        val open = runCatching { commands.handle("open $gameName") }.getOrNull()
            ?: return Result(true, toolFailure = "I could not resolve the game named $gameName.")
        if (!open.ok) return Result(true, toolFailure = open.message)

        // Give Android and the game time to pass splash/loading screens before interacting.
        delay(5000)
        val service = AstraAccessibilityService.current()
            ?: return Result(true, toolFailure = "$gameName opened, but Astra Accessibility Access is not enabled, so I cannot inspect or press its Start button.")

        var screen = service.readScreen().trim().take(24000)
        repeat(3) {
            if (looksLikeLoading(screen)) {
                delay(3000)
                screen = service.readScreen().trim().take(24000)
            }
        }

        // Only interact with an explicit visible start control. Never open Settings as fallback.
        val startLabels = listOf("Start", "START", "Play", "PLAY", "Play Game", "Start Game", "Continue", "CONTINUE", "Begin", "BEGIN", "Start Playing", "Play Now")
        var clicked = false
        for (label in startLabels) {
            if (service.clickText(label)) { clicked = true; delay(1500); break }
        }
        screen = service.readScreen().trim().take(24000)

        val request = if (clicked) {
            """
The user asked: $original
Astra opened $gameName, waited for loading to settle, observed the game, and pressed a visible Start/Play/Continue/Begin control.
Continue only from the CURRENT SCREEN TEXT. Do not leave the game or open Android Settings unless explicitly requested. Do not claim the game is fully played unless verified.
Do not automate actions that provide an unfair advantage in competitive multiplayer games.
CURRENT SCREEN TEXT:
$screen
""".trimIndent()
        } else {
            """
The user asked: $original
Astra opened $gameName and waited for its loading state. No obvious Start/Play/Continue/Begin control was exposed to Android Accessibility.
Inspect the CURRENT SCREEN TEXT and report the actual state. Do NOT open Android Settings as a fallback. If it is still loading, say so. If a permission/login/update prompt is visible, identify it.
CURRENT SCREEN TEXT:
$screen
""".trimIndent()
        }
        return Result(true, reasoningRequest = request)
    }

    private fun looksLikeLoading(screen: String): Boolean {
        if (screen.isBlank()) return true
        val s = screen.lowercase()
        return s.contains("loading") || s.contains("please wait") || s.contains("connecting") || s.contains("starting") || s.contains("initializing") || s.contains("downloading") || s.contains("checking for updates")
    }

    private suspend fun planReadChat(original: String, appName: String, sender: String): Result {
        val open = runCatching { commands.handle("open ${appName.removePrefix("the ").trim()}") }.getOrNull()
            ?: return Result(true, toolFailure = "I could not resolve the app named $appName.")
        if (!open.ok) return Result(true, toolFailure = open.message)
        delay(1800)
        if (appName.contains("whatsapp", ignoreCase = true)) {
            val service = AstraAccessibilityService.current() ?: return Result(true, toolFailure = "WhatsApp is open, but Astra's Accessibility Access is not enabled.")
            for (label in listOf("Search", "Search…", "Search...", "Search chats")) {
                if (service.clickText(label)) {
                    delay(300)
                    if (!service.typeText(sender)) return Result(true, toolFailure = "I opened WhatsApp but could not type the requested sender into its search field.")
                    delay(1000)
                    var selected = service.clickText(sender)
                    if (!selected) {
                        val digits = sender.filter(Char::isDigit)
                        if (digits.length >= 8) selected = service.clickText(digits)
                    }
                    if (selected) delay(1400)
                    break
                }
            }
        }
        val screen = AstraAccessibilityService.current()?.readScreen().orEmpty().trim().take(24000)
        if (screen.isBlank()) return Result(true, toolFailure = "The app opened, but Astra could not read its screen. Enable Accessibility Access and keep the requested chat visible.")
        return Result(true, reasoningRequest = """
The user asked:
$original
Astra has opened $appName and attempted visible accessibility navigation.
Use the CURRENT SCREEN TEXT as the only evidence. Requested sender: $sender
Identify the latest visibly attributable message. Never invent or guess content. If the sender/chat/message cannot be verified, say so. Do not expose unrelated private content.
CURRENT SCREEN TEXT:
$screen
""".trimIndent())
    }

    private fun splitSteps(text: String): List<String> = Regex("\\s+and\\s+(?=(?:open|launch|start|run|click|tap|type|enter|read|check|inspect|look|show|tell|find|search|scroll|go|take|capture|send|call|dial|reply|write|compose)\\b)", RegexOption.IGNORE_CASE).split(text).map { it.trim() }.filter { it.isNotBlank() }
    private fun isAppOpenStep(step: String): Boolean = Regex("^(?:please\\s+|could\\s+you\\s+|can\\s+you\\s+|hey\\s+astra\\s+|astra\\s+)*(?:open|launch|start|run)\\s+.+$", RegexOption.IGNORE_CASE).matches(step.trim())
    private fun isNavigationStep(step: String): Boolean = Regex("^(?:click|tap|type|enter|scroll|go|search)\\b.*$", RegexOption.IGNORE_CASE).matches(step.trim())
    private fun prepareOpenedApp(step: String, wholeRequest: String) {
        val normalized = step.lowercase(); if (!normalized.contains("whatsapp")) return
        val number = Regex("(?:\\+?\\d[\\d\\s().-]{7,}\\d)").find(wholeRequest)?.value?.filter { it.isDigit() }?.takeIf { it.length >= 8 } ?: return
        runCatching { app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$number")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
    private fun buildReasoningRequest(original: String, remainder: String): String = """
The user gave this multi-step request:
$original
Astra already executed the supported navigation steps. Now complete the remaining instruction:
$remainder
Treat live screen text as the source of truth. Never invent results. Answer the user's actual request rather than merely saying that an app was opened. Do not expose unrelated private content.
""".trimIndent()
}
