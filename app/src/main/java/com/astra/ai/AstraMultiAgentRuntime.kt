package com.astra.ai

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Multi-agent coordination layer. Astra remains the main reasoning AI; specialist agents
 * observe/prepare information in parallel and the executor performs only permitted actions.
 */
class AstraMultiAgentRuntime(context: Context) {
    private val appContext = context.applicationContext
    private val planner = AstraAgenticExecutor(appContext)
    private val state = AstraAgentStateBus(appContext)
    private val screen = AstraScreenAgent(appContext)
    private val chat = AstraChatAgent(appContext)
    private val files = AstraFileAgent(appContext)
    private val dispatcher = AstraInstructionDispatcher(appContext)
    private val synthesizer = AstraResponseSynthesizer(appContext)

    suspend fun run(userRequest: String, plannerAnswer: suspend (String) -> String): Result? = coroutineScope {
        val clean = userRequest.trim()
        if (clean.isBlank()) return@coroutineScope null

        val classification = dispatcher.classify(clean, plannerAnswer)
        state.publish("request", clean)
        state.publish("classification", classification.toString())

        val observations = listOf(
            async { screen.observe() },
            async { chat.observe(clean) },
            async { files.observe(clean) }
        ).awaitAll()
        observations.forEach { state.publish(it.agent, it.data) }

        if (!classification.requiresDeviceAction && !classification.isCompound) return@coroutineScope null

        val instruction = dispatcher.buildExecutionInstruction(clean, classification, observations, state.snapshot())
        val execution = runCatching {
            planner.run(instruction) { planningPrompt -> plannerAnswer(planningPrompt) }
        }.getOrElse { AstraAgenticExecutor.Result(false, "Agent execution failed: ${it.message ?: "unknown error"}") }
        state.publish("execution", execution.answer)

        val unified = synthesizer.synthesize(clean, observations, execution, state.snapshot(), plannerAnswer)
        Result(true, unified)
    }

    data class Result(val handled: Boolean, val answer: String)
}

data class AstraAgentObservation(val agent: String, val data: String)

data class AstraTaskClassification(
    val isCompound: Boolean,
    val requiresDeviceAction: Boolean,
    val domains: List<String>
)

class AstraAgentStateBus(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("astra_agent_state", Context.MODE_PRIVATE)
    fun publish(key: String, value: String) { prefs.edit().putString(key, value.take(20000)).apply() }
    fun snapshot(): Map<String, String> = prefs.all.mapNotNull { (k, v) -> if (v is String) k to v else null }.toMap()
}

class AstraScreenAgent(private val context: Context) {
    fun observe(): AstraAgentObservation {
        val text = AstraAccessibilityService.current()?.readScreen().orEmpty().trim()
        return AstraAgentObservation("screen", if (text.isBlank()) "SCREEN_UNAVAILABLE" else text.take(12000))
    }
}

class AstraChatAgent(private val context: Context) {
    fun observe(request: String): AstraAgentObservation {
        val store = AstraChatStore(context.applicationContext)
        val id = context.applicationContext.getSharedPreferences("astra_runtime", Context.MODE_PRIVATE).getString("current_chat_id", "")
        val history = if (id.isNullOrBlank()) emptyList() else store.recentMessages(id, oneYear = true, limit = 12)
        val data = history.joinToString("\n") { "${it.role}: ${it.text}" }
        return AstraAgentObservation("chat", if (data.isBlank()) "CHAT_CONTEXT_EMPTY" else data.take(8000))
    }
}

class AstraFileAgent(private val context: Context) {
    fun observe(request: String): AstraAgentObservation {
        val workspace = AstraWorkspace(context.applicationContext).contextText()
        return AstraAgentObservation("files", if (workspace.isBlank()) "FILE_CONTEXT_EMPTY" else workspace.take(10000))
    }
}

class AstraInstructionDispatcher(private val context: Context) {
    suspend fun classify(request: String, ai: suspend (String) -> String): AstraTaskClassification {
        val lower = request.lowercase()
        val device = Regex("\\b(open|close|tap|click|press|type|swipe|scroll|send|call|dial|launch|search|find|read|reply|message|whatsapp|telegram|setting|screenshot|screen|camera)\\b").containsMatchIn(lower)
        val compound = listOf(" and ", " then ", " after ", " before ", " also ", "while ", "followed by").any { lower.contains(it) }
        val domains = buildList {
            if (device) add("device")
            if (lower.contains("screen") || lower.contains("screenshot")) add("screen")
            if (lower.contains("message") || lower.contains("chat") || lower.contains("reply")) add("chat")
            if (lower.contains("file") || lower.contains("pdf") || lower.contains("document")) add("files")
            if (lower.contains("api") || lower.contains("http") || lower.contains("web")) add("web_api")
            if (lower.contains("remember") || lower.contains("memory")) add("memory")
        }
        // Classification is deliberately deterministic first. The main AI is asked for richer
        // planning only after routing, avoiding an expensive model call for every simple chat.
        return AstraTaskClassification(compound, device, domains.ifEmpty { listOf("chat") })
    }

    fun buildExecutionInstruction(
        request: String,
        classification: AstraTaskClassification,
        observations: List<AstraAgentObservation>,
        state: Map<String, String>
    ): String = buildString {
        appendLine("Execute this user goal as a closed-loop task graph.")
        appendLine("USER GOAL: $request")
        appendLine("DOMAINS: ${classification.domains.joinToString()}")
        appendLine("Use parallel work only for independent observations. Preserve dependencies for actions.")
        appendLine("Verify every important action by observing the resulting screen/state. Never claim success without evidence.")
        observations.forEach { appendLine("${it.agent.uppercase()} AGENT DATA:\n${it.data}") }
    }
}

class AstraResponseSynthesizer(private val context: Context) {
    suspend fun synthesize(
        request: String,
        observations: List<AstraAgentObservation>,
        execution: AstraAgenticExecutor.Result,
        state: Map<String, String>,
        ai: suspend (String) -> String
    ): String {
        if (execution.answer.isNotBlank() && execution.answer != "Done.") return execution.answer
        val evidence = observations.joinToString("\n") { "${it.agent}: ${it.data.take(4000)}" }
        val prompt = "Return one concise user-readable result for this task. Do not invent facts. Goal: $request\nExecution: ${execution.answer}\nEvidence:\n$evidence"
        return runCatching { ai(prompt) }.getOrElse { execution.answer.ifBlank { "I could not complete that task." } }
    }
}
