package com.astra.ai

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Deterministic action planner for multi-step requests.
 *
 * The important rule is: do not let a generic "open <app>" parser consume a
 * request that contains additional work.  High-confidence device actions run
 * first; the language model is used only for interpretation/extraction.
 */
class AstraActionPlanner(private val context: Context) {
    private val app = context.applicationContext

    suspend fun tryExecute(request: String, answerFromContext: suspend (String) -> String): String? {
        val text = request.trim()
        val lower = text.lowercase(Locale.ROOT)

        // Example: "open whatsapp and read the last message sent by +919..."
        val readChat = Regex(
            "(?is)^open\\s+(.+?)\\s+and\\s+(?:read|tell me)\\s+(?:the\\s+)?(?:last|latest)\\s+message\\s+(?:sent\\s+by|from)\\s+(.+?)\\s*$"
        ).find(text)

        if (readChat != null) {
            val appName = readChat.groupValues[1].trim()
            val sender = readChat.groupValues[2].trim()
            return executeReadLastMessage(appName, sender, answerFromContext)
        }

        // Also understand the common wording "open X and tell me what Y says".
        val screenAfterOpen = Regex("(?is)^open\\s+(.+?)\\s+and\\s+(?:read|tell me)\\s+(.+)$").find(text)
        if (screenAfterOpen != null && (lower.contains("screen") || lower.contains("message") || lower.contains("chat"))) {
            val appName = screenAfterOpen.groupValues[1].trim()
            val requestedContent = screenAfterOpen.groupValues[2].trim()
            val opened = openApp(appName)
            if (!opened) return "I couldn't open $appName. Check that the app is installed and Astra has permission to launch apps."
            waitForScreen(1800)
            val screen = readScreen()
            if (screen.isBlank()) return "I opened $appName, but Astra cannot read its screen. Enable Accessibility Access for Astra and try again."
            return answerFromContext("Read the requested information from the CURRENT SCREEN. User asked: $requestedContent\\n\\nCURRENT SCREEN:\\n$screen")
        }
        return null
    }

    private suspend fun executeReadLastMessage(appName: String, sender: String, answerFromContext: suspend (String) -> String): String {
        val opened = openApp(appName)
        if (!opened) return "I couldn't open $appName. Check that it is installed and Astra has permission to launch apps."

        waitForScreen(1800)

        // WhatsApp is intentionally handled through visible Android UI/accessibility,
        // not through private databases or hidden APIs.
        if (appName.lowercase(Locale.ROOT).contains("whatsapp")) {
            val service = AstraAccessibilityService.current()
                ?: return "WhatsApp is open, but Astra cannot control/read it yet. Enable Accessibility Access for Astra."

            val searchClicked = clickFirst(service, listOf("Search", "Search…", "Search...", "search"))
            if (searchClicked) {
                delay(250)
                service.typeText(sender)
                delay(1000)

                // Prefer the exact number/name, then a digits-only variant.
                var selected = service.clickText(sender)
                if (!selected) {
                    val digits = sender.filter(Char::isDigit)
                    if (digits.isNotBlank()) selected = service.clickText(digits)
                }
                if (selected) delay(1200)
            }
        }

        val screen = readScreen()
        if (screen.isBlank()) {
            return "I opened $appName, but Astra could not read the current screen. Keep the chat visible and ensure Accessibility Access is enabled."
        }

        val instruction = """
Read the requested chat/message information from the CURRENT SCREEN below.
The user specifically asked for the latest message sent by: $sender
App: $appName

Rules:
- Do not greet or introduce yourself.
- Do not repeat UI labels such as Send, Camera, Search, tabs, buttons, or Astra controls.
- Distinguish the requested sender from the user's own messages.
- If the visible screen does not contain enough evidence to identify that sender's latest message, say exactly that instead of guessing.
- If the requested chat is not open, say that it could not be located rather than inventing a message.
- Return the message content concisely, and include the sender only when useful.

CURRENT SCREEN:
$screen
""".trimIndent()
        return answerFromContext(instruction)
    }

    private fun openApp(name: String): Boolean {
        // Let the existing app resolver handle aliases/package lookup first.
        val clean = name.removePrefix("the ").trim()
        val resolver = LocalCommandEngine(app)
        return runCatching {
            kotlinx.coroutines.runBlocking {
                resolver.handle("open $clean")?.success == true
            }
        }.getOrDefault(false)
    }

    private fun clickFirst(service: AstraAccessibilityService, labels: List<String>): Boolean {
        for (label in labels) if (service.clickText(label)) return true
        return false
    }

    private suspend fun waitForScreen(ms: Long) {
        delay(ms.coerceIn(300, 3500))
    }

    private fun readScreen(): String = AstraAccessibilityService.current()?.readScreen().orEmpty().trim().take(24000)
}
