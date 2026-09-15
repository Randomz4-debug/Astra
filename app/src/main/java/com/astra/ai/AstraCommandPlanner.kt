package com.astra.ai

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Small deterministic planner for multi-step natural-language commands.
 *
 * The normal command router is intentionally fast and deterministic, but it used to stop after
 * the first executable verb. This planner keeps the whole request alive: it executes the steps
 * that are actually supported, waits for the target app to settle, then hands the remaining
 * question to Astra's reasoning model with fresh screen context.
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
                    prepareOpenedApp(step)
                    delay(1100)
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

        Result(
            handled = true,
            reasoningRequest = buildReasoningRequest(clean, remainder!!)
        )
    }

    private fun splitSteps(text: String): List<String> {
        // Only split when "and" is followed by an action/inspection verb. This avoids breaking
        // ordinary sentences such as "open Maps and search for food and coffee" unnecessarily.
        val parts = Regex(
            "\\s+and\\s+(?=(?:open|launch|start|run|click|tap|type|enter|read|check|inspect|look|show|tell|find|search|scroll|go|take|capture|send|call|dial|reply|write|compose)\\b)",
            RegexOption.IGNORE_CASE
        ).split(text).map { it.trim() }.filter { it.isNotBlank() }
        return parts
    }

    private fun isAppOpenStep(step: String): Boolean = Regex(
        "^(?:please\\s+|could\\s+you\\s+|can\\s+you\\s+|hey\\s+astra\\s+|astra\\s+)*(?:open|launch|start|run)\\s+.+$",
        RegexOption.IGNORE_CASE
    ).matches(step.trim())

    private fun isNavigationStep(step: String): Boolean = Regex(
        "^(?:click|tap|type|enter|scroll|go|search)\\b.*$",
        RegexOption.IGNORE_CASE
    ).matches(step.trim())

    private fun prepareOpenedApp(step: String) {
        val normalized = step.lowercase()
        if (!normalized.contains("whatsapp")) return

        // WhatsApp's official Click-to-Chat link opens the conversation for a full international
        // number. This is preferable to guessing at WhatsApp's changing search UI.
        val number = Regex("(?:\\+?\\d[\\d\\s().-]{7,}\\d)")
            .find(step)
            ?.value
            ?.filter { it.isDigit() }
            ?.takeIf { it.length >= 8 }
            ?: return

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$number"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { app.startActivity(intent) }
    }

    private fun buildReasoningRequest(original: String, remainder: String): String {
        val screen = AstraAccessibilityService.current()?.readScreen().orEmpty().trim()
        return """
The user gave this multi-step request:
$original

Astra already executed the supported navigation steps. Now complete the remaining instruction:
$remainder

IMPORTANT EXECUTION/ACCURACY RULES:
- Use the CURRENT SCREEN TEXT supplied by AstraAgentRuntime as the source of truth for what is visible.
- If this is a WhatsApp/message-reading request, identify the requested conversation/sender first, then identify the most recent message actually visible from that sender.
- Do not invent, reconstruct, or guess message contents. If the requested message is not visible or the sender cannot be verified, say exactly that and explain what Astra needs to do next.
- Do not claim that a screen action happened unless the execution layer reported success.
- Answer the user's actual question, not merely the fact that an app was opened.
- If more screen navigation is genuinely necessary, describe the exact next action instead of pretending it happened.

The screen snapshot observed immediately after navigation was:
${if (screen.isBlank()) "(screen text unavailable)" else screen.take(18000)}
        """.trimIndent()
    }
}
