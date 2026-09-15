package com.astra.ai

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Deterministic planner for multi-step natural-language commands.
 * The planner must finish the requested sequence instead of returning after
 * the first verb (for example, "open WhatsApp" must not swallow "read ...").
 */
class AstraCommandPlanner(context: Context) {
    data class Result(
        val handled: Boolean,
        val toolFailure: String? = null,
        val reasoningRequest: String? = null
    )

    private val app = context.applicationContext
    private val commands = LocalCommandEngine(app)

    suspend fun plan(input: String): Result? = withContext(Dispatchers.IO) {
        val clean = input.trim()
        if (clean.isBlank()) return@withContext null

        // High-confidence chat-reading command. Execute the navigation ourselves so the
        // model cannot accidentally answer with a greeting or stop after "open WhatsApp".
        val readChat = Regex(
            "(?is)^open\\s+(.+?)\\s+and\\s+(?:read|tell me)\\s+(?:the\\s+)?(?:last|latest)\\s+message\\s+(?:sent\\s+by|from)\\s+(.+?)\\s*$"
        ).find(clean)
        if (readChat != null) return@withContext planReadChat(clean, readChat.groupValues[1].trim(), readChat.groupValues[2].trim())

        val steps = splitSteps(clean)
        if (steps.size < 2) return@withContext null

        var executed = 0
        var lastFailure: String? = null
        var remainder: String? = null

        for ((index, step) in steps.withIndex()) {
            val tool = runCatching { commands.handle(step) }.getOrNull()
            if (tool != null) {
                if (!tool.ok) {
                    lastFailure = tool.message
                    break
                }
                executed++
                if (isAppOpenStep(step)) {
                    prepareOpenedApp(step, clean)
                    delay(1500)
                } else if (isNavigationStep(step)) {
                    delay(650)
                }
            } else {
                remainder = steps.drop(index).joinToString(" and ")
                break
            }
        }

        if (executed == 0) return@withContext null
        if (lastFailure != null) return@withContext Result(true, toolFailure = lastFailure)
        if (remainder.isNullOrBlank()) return@withContext Result(true)
        Result(true, reasoningRequest = buildReasoningRequest(clean, remainder!!))
    }

    private suspend fun planReadChat(original: String, appName: String, sender: String): Result {
        val open = runCatching { commands.handle("open ${appName.removePrefix("the ").trim()}") }.getOrNull()
        if (open == null) return Result(true, toolFailure = "I could not resolve the app named $appName.")
        if (!open.ok) return Result(true, toolFailure = open.message)

        delay(1800)

        if (appName.contains("whatsapp", ignoreCase = true)) {
            val service = AstraAccessibilityService.current()
                ?: return Result(true, toolFailure = "WhatsApp is open, but Astra's Accessibility Access is not enabled.")

            // WhatsApp's normal Android UI can be searched by contact name or phone number.
            // Use only visible controls exposed to Accessibility; no private database access.
            val searchLabels = listOf("Search", "Search…", "Search...", "Search chats")
            var searchClicked = false
            for (label in searchLabels) {
                if (service.clickText(label)) { searchClicked = true; break }
            }
            if (searchClicked) {
                delay(300)
                if (!service.typeText(sender)) {
                    return Result(true, toolFailure = "I opened WhatsApp but could not type the requested sender into its search field.")
                }
                delay(1000)

                var selected = service.clickText(sender)
                if (!selected) {
                    val digits = sender.filter(Char::isDigit)
                    if (digits.length >= 8) selected = service.clickText(digits)
                }
                if (selected) delay(1400)
            } else {
                // If WhatsApp is already showing the requested chat, continue. Otherwise give
                // the reasoning model the visible screen and let it report that the chat is not open.
                delay(500)
            }
        }

        val screen = AstraAccessibilityService.current()?.readScreen().orEmpty().trim().take(24000)
        if (screen.isBlank()) {
            return Result(true, toolFailure = "The app opened, but Astra could not read its screen. Enable Accessibility Access and keep the requested chat visible.")
        }

        return Result(
            handled = true,
            reasoningRequest = """
The user asked:
$original

Astra has already opened $appName and attempted to navigate using visible Android accessibility controls.
Use the CURRENT SCREEN TEXT below as the only evidence for the answer.

The requested sender is: $sender

Rules:
- Answer the actual request, not merely that the app was opened.
- Identify the latest message that is visibly attributable to the requested sender.
- Distinguish the requested sender's message from the user's own messages, timestamps, headers and UI controls.
- Do not invent, infer, or guess a message that is not visible.
- If the requested chat/sender/message cannot be verified from the screen, say that clearly.
- Do not greet or introduce yourself.
- Do not repeat unrelated UI labels.
- Do not expose unrelated private content.

CURRENT SCREEN TEXT:
$screen
""".trimIndent()
        )
    }

    private fun splitSteps(text: String): List<String> = Regex(
        "\\s+and\\s+(?=(?:open|launch|start|run|click|tap|type|enter|read|check|inspect|look|show|tell|find|search|scroll|go|take|capture|send|call|dial|reply|write|compose)\\b)",
        RegexOption.IGNORE_CASE
    ).split(text).map { it.trim() }.filter { it.isNotBlank() }

    private fun isAppOpenStep(step: String): Boolean = Regex(
        "^(?:please\\s+|could\\s+you\\s+|can\\s+you\\s+|hey\\s+astra\\s+|astra\\s+)*(?:open|launch|start|run)\\s+.+$",
        RegexOption.IGNORE_CASE
    ).matches(step.trim())

    private fun isNavigationStep(step: String): Boolean = Regex(
        "^(?:click|tap|type|enter|scroll|go|search)\\b.*$",
        RegexOption.IGNORE_CASE
    ).matches(step.trim())

    private fun prepareOpenedApp(step: String, wholeRequest: String) {
        val normalized = step.lowercase()
        if (!normalized.contains("whatsapp")) return
        val number = Regex("(?:\\+?\\d[\\d\\s().-]{7,}\\d)")
            .find(wholeRequest)?.value?.filter { it.isDigit() }
            ?.takeIf { it.length >= 8 } ?: return
        runCatching {
            app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$number")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun buildReasoningRequest(original: String, remainder: String): String = """
The user gave this multi-step request:
$original

Astra already executed the supported navigation steps. Now complete the remaining instruction:
$remainder

ACCURACY RULES:
- Treat the live screen text as the source of truth for what is visible.
- For a WhatsApp/message-reading request, verify the requested conversation/sender first, then identify the most recent message actually visible from that sender.
- Never invent, reconstruct, or guess message contents.
- If the requested message is not visible or the sender cannot be verified, say so clearly.
- Never claim an action succeeded unless the execution layer reported success.
- Answer the user's actual request rather than merely saying that an app was opened.
- Do not expose unrelated private screen content.
""".trimIndent()
}
