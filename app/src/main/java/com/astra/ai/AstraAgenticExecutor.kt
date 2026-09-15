package com.astra.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Closed-loop agent controller. The model plans, Astra executes, Astra observes the new
 * screen/state, and the model decides the next action. This prevents the old "first verb wins"
 * behaviour where an instruction such as "open WhatsApp and read..." stopped after opening it.
 */
class AstraAgenticExecutor(context: Context) {
    private val app = context.applicationContext
    private val router = ToolRouter(app)

    data class Result(val handled: Boolean, val answer: String, val steps: Int)

    suspend fun run(goal: String, model: suspend (String) -> String): Result = withContext(Dispatchers.IO) {
        val trace = StringBuilder()
        var lastObservation = screen()
        var executed = 0

        repeat(10) { round ->
            val prompt = buildPlannerPrompt(goal, trace.toString(), lastObservation, round + 1)
            val raw = runCatching { model(prompt) }.getOrNull().orEmpty()
            val plan = parsePlan(raw) ?: return@withContext Result(
                handled = executed > 0,
                answer = if (executed > 0) "I completed the actions I could verify, but the AI planner returned an invalid next step." else "",
                steps = executed
            )

            val actions = plan.optJSONArray("actions")
            if (actions != null) {
                for (i in 0 until actions.length()) {
                    val action = actions.optJSONObject(i) ?: continue
                    val name = action.optString("tool").trim()
                    val argsObject = action.optJSONObject("args") ?: JSONObject()
                    if (name.isBlank() || !router.tools.any { it.name == name }) continue
                    val args = mutableMapOf<String, String>()
                    argsObject.keys().forEach { key -> args[key] = argsObject.optString(key) }
                    val result = runCatching { router.execute(name, args, confirmed = false) }
                        .getOrElse { ToolResult(false, "Tool crashed: ${it.message ?: "unknown error"}") }
                    executed++
                    trace.append("STEP ").append(executed).append(" ").append(name).append(" ")
                        .append(args).append(" -> ").append(if (result.ok) "OK: " else "FAILED: ")
                        .append(result.message.take(3000)).append("\n")
                    delay(actionDelay(name))
                    lastObservation = screen()
                    trace.append("OBSERVATION: ").append(lastObservation.take(6000)).append("\n")
                    if (!result.ok) break
                }
            }

            val done = plan.optBoolean("done", false)
            val answer = plan.optString("say").trim()
            if (done && answer.isNotBlank()) {
                return@withContext Result(true, answer, executed)
            }
            if (done && executed > 0) return@withContext Result(true, "Done.", executed)
        }

        Result(executed > 0, if (executed > 0) "I completed the verified actions, but the task needs another step that Astra could not safely execute." else "", executed)
    }

    private fun buildPlannerPrompt(goal: String, trace: String, screen: String, round: Int): String = """
You are Astra's action-planning brain. You control a real Android device through a restricted tool layer.
You are NOT a chatbot that should answer after the first action. You are a closed-loop agent: PLAN -> EXECUTE -> OBSERVE -> PLAN AGAIN until the user's goal is actually complete.

USER GOAL:
$goal

ROUND: $round / 10

AVAILABLE TOOLS:
${router.tools.joinToString("\n") { "- ${it.name}: ${it.description}" }}

EXECUTION TRACE:
${trace.ifBlank { "(none yet)" }}

CURRENT VISIBLE SCREEN:
${screen.ifBlank { "(unavailable)" }}

Return ONLY one JSON object, no markdown:
{"done":false,"say":"","actions":[{"tool":"openApp","args":{"name":"WhatsApp"}}]}

Rules:
1. Choose concrete actions, not prose instructions.
2. You may output multiple actions, but after navigation to another app prefer a small batch so the next round can observe the changed screen.
3. Do not stop merely because an app opened. Continue until the user's requested information/action is completed.
4. Use the visible screen as evidence. If a target is not visible, search/navigate using available tools rather than inventing it.
5. For reading a message: verify the conversation and sender from visible UI, then read the actual message. Never guess.
6. For WhatsApp contact requests, open WhatsApp, use its visible Search control, search the requested number/name, open the matching result, then inspect the conversation.
7. If a tool fails, recover with another available action when possible.
8. Never claim a login, connection, message read, message sent, or other action succeeded unless the tool result and/or visible screen verifies it.
9. `say` is the final answer to the user, not a status update. Keep it empty until done.
10. Never greet or say "Hi, I am Astra" for an ongoing task.
11. Do not expose unrelated private screen content.
""".trimIndent()

    private fun parsePlan(raw: String): JSONObject? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(raw.substring(start, end + 1)) }.getOrNull()
    }

    private fun screen(): String = AstraAccessibilityService.current()?.readScreen().orEmpty().trim().take(18000)

    private fun actionDelay(name: String): Long = when (name) {
        "openApp", "openBrowser", "openMaps", "openCamera" -> 1400L
        "clickScreen", "typeScreen", "tapScreen" -> 650L
        "scrollScreen", "swipeScreen" -> 800L
        else -> 300L
    }
}
