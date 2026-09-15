package com.astra.ai

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Multi-agent coordination: parallel perception/context followed by verified execution. */
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
        val classification = dispatcher.classify(clean)
        state.publish("request", clean)
        state.publish("classification", classification.toString())
        val observations = listOf(
            async { screen.observe() },
            async { chat.observe() },
            async { files.observe() }
        ).awaitAll()
        observations.forEach { state.publish(it.agent, it.data) }
        if (!classification.requiresDeviceAction && !classification.isCompound) return@coroutineScope null
        val instruction = dispatcher.buildExecutionInstruction(clean, classification, observations)
        val execution = runCatching {
            planner.run(instruction) { prompt -> plannerAnswer(prompt) }
        }.getOrElse { AstraAgenticExecutor.Result(false, "Agent execution failed: ${it.message ?: "unknown error"}", 0) }
        state.publish("execution", execution.answer)
        val unified = synthesizer.synthesize(clean, observations, execution, plannerAnswer)
        Result(true, unified)
    }

    data class Result(val handled: Boolean, val answer: String)
}

data class AstraAgentObservation(val agent: String, val data: String)
data class AstraTaskClassification(val isCompound: Boolean, val requiresDeviceAction: Boolean, val domains: List<String>)

class AstraAgentStateBus(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("astra_agent_state", Context.MODE_PRIVATE)
    fun publish(key: String, value: String) { prefs.edit().putString(key, value.take(20000)).apply() }
}

class AstraScreenAgent(private val context: Context) {
    fun observe(): AstraAgentObservation {
        val text = AstraAccessibilityService.current()?.readScreen().orEmpty().trim()
        return AstraAgentObservation("screen", if (text.isBlank()) "SCREEN_UNAVAILABLE" else text.take(12000))
    }
}

class AstraChatAgent(private val context: Context) {
    fun observe(): AstraAgentObservation {
        val app = context.applicationContext
        val store = AstraChatStore(app)
        val id = app.getSharedPreferences("astra_runtime", Context.MODE_PRIVATE).getString("current_chat_id", "")
        val history = if (id.isNullOrBlank()) emptyList() else store.recentMessages(id, oneYear = true, limit = 12)
        val data = history.joinToString("\n") { "${it.role}: ${it.text}" }
        return AstraAgentObservation("chat", if (data.isBlank()) "CHAT_CONTEXT_EMPTY" else data.take(8000))
    }
}

class AstraFileAgent(private val context: Context) {
    suspend fun observe(): AstraAgentObservation {
        val data = AstraWorkspace(context.applicationContext).contextText()
        return AstraAgentObservation("files", if (data.isBlank()) "FILE_CONTEXT_EMPTY" else data.take(10000))
    }
}

class AstraInstructionDispatcher(private val context: Context) {
    fun classify(request: String): AstraTaskClassification {
        val n = request.lowercase()
        val device = Regex("\\b(open|close|tap|click|press|type|swipe|scroll|send|call|dial|launch|search|find|read|reply|message|whatsapp|telegram|setting|screenshot|screen|camera)\\b").containsMatchIn(n)
        val compound = listOf(" and ", " then ", " after ", " before ", " also ", " while ", " followed by ").any(n::contains)
        val domains = buildList {
            if (device) add("device")
            if (n.contains("screen") || n.contains("screenshot")) add("screen")
            if (n.contains("message") || n.contains("chat") || n.contains("reply")) add("chat")
            if (n.contains("file") || n.contains("pdf") || n.contains("document")) add("files")
            if (n.contains("api") || n.contains("http") || n.contains("web")) add("web_api")
            if (n.contains("remember") || n.contains("memory")) add("memory")
        }
        return AstraTaskClassification(compound, device, domains.ifEmpty { listOf("chat") })
    }

    fun buildExecutionInstruction(request: String, classification: AstraTaskClassification, observations: List<AstraAgentObservation>): String = buildString {
        appendLine("Execute this user goal as a closed-loop task graph.")
        appendLine("USER GOAL: $request")
        appendLine("DOMAINS: ${classification.domains.joinToString()}")
        appendLine("Independent observations may run in parallel. Actions with dependencies must remain ordered.")
        appendLine("Verify important actions by observing resulting screen/state. Never claim success without evidence.")
        observations.forEach { appendLine("${it.agent.uppercase()} AGENT DATA:\n${it.data}") }
    }
}

class AstraResponseSynthesizer(private val context: Context) {
    suspend fun synthesize(request: String, observations: List<AstraAgentObservation>, execution: AstraAgenticExecutor.Result, ai: suspend (String) -> String): String {
        if (execution.answer.isNotBlank() && execution.answer != "Done.") return execution.answer
        val evidence = observations.joinToString("\n") { "${it.agent}: ${it.data.take(4000)}" }
        val prompt = "Return one concise user-readable result. Do not invent facts. Goal: $request\nExecution: ${execution.answer}\nEvidence:\n$evidence"
        return runCatching { ai(prompt) }.getOrElse { execution.answer.ifBlank { "I could not complete that task." } }
    }
}
