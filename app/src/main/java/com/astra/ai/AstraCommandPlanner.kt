package com.astra.ai

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Deterministic planner for multi-step natural-language commands.
 * The old router returned after the first successful verb; this keeps the whole request alive.
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
                    delay(1300)
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

        // WhatsApp officially supports Click-to-Chat links. Use the user's exact international
        // number when present so the next screen is the requested conversation, not the home tab.
        val number = Regex("(?:\\+?\\d[\\d\\s().-]{7,}\\d)")
            .find(wholeRequest)
            ?.value
            ?.filter { it.isDigit() }
            ?.takeIf { it.length >= 8 }
            ?: return

        runCatching {
            app.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$number"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
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
- If the requested message is not visible or the sender cannot be verified, say so clearly and state the next required screen action.
- Never claim an action succeeded unless the execution layer reported success.
- Answer the user's actual request rather than merely saying that an app was opened.
- Do not expose unrelated private screen content.
""".trimIndent()
}
