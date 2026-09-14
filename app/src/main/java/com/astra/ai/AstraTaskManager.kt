package com.astra.ai

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Plans larger user requests into small ordered steps and executes them sequentially. */
class AstraTaskManager(context: Context) {
    data class TaskState(val id: String, val prompt: String, val step: Int, val total: Int, val status: String, val result: String = "")
    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val jobs = ConcurrentHashMap<String, Job>()
        private val states = ConcurrentHashMap<String, TaskState>()
    }
    private val appContext = context.applicationContext

    fun start(prompt: String): String {
        val id = UUID.randomUUID().toString().take(8)
        states[id] = TaskState(id, prompt, 0, 0, "queued")
        jobs[id] = scope.launch {
            runCatching {
                val runtime = AstraAgentRuntime(appContext)
                val planText = runtime.automationReason("Break this user request into the smallest useful ordered execution steps. Output only one concrete step per line, maximum 12 lines. If it is already a single action, output it unchanged. User request:\n$prompt")
                val steps = planText.lines().map { it.trim().replace(Regex("^[-*•]\\s*"), "").replace(Regex("^\\d+[.)]\\s*"), "") }.filter { it.isNotBlank() }.take(12).ifEmpty { listOf(prompt) }
                states[id] = TaskState(id, prompt, 0, steps.size, "running")
                val results = mutableListOf<String>()
                for ((index, step) in steps.withIndex()) {
                    states[id] = TaskState(id, prompt, index + 1, steps.size, "running")
                    results += runtime.handle(step, localOnly = false)
                }
                states[id] = TaskState(id, prompt, steps.size, steps.size, "completed", results.lastOrNull().orEmpty())
            }.onFailure { states[id] = TaskState(id, prompt, 0, 0, "failed", it.message ?: "Task failed") }
        }
        return id
    }

    fun state(id: String): TaskState? = states[id]
    fun all(): List<TaskState> = states.values.sortedBy { it.id }
    fun stop(id: String): Boolean {
        val job = jobs.remove(id) ?: return false
        job.cancel()
        states[id]?.let { states[id] = it.copy(status = "stopped") }
        return true
    }
    fun stopAll() { jobs.keys.toList().forEach { stop(it) } }
}
