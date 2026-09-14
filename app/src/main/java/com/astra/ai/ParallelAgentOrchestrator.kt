package com.astra.ai

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject

/** Runs independent sub-agent prompts concurrently and returns results in input order. */
class ParallelAgentOrchestrator(context: Context) {
    private val appContext = context.applicationContext
    suspend fun run(tasks: List<String>): List<String> = runSpecs(tasks.map { JSONObject().put("task", it) })
    suspend fun runSpecs(tasks: List<JSONObject>): List<String> = coroutineScope {
        tasks.map { spec ->
            async {
                val task = spec.optString("task")
                val instruction = spec.optString("instruction")
                val prompt = if (instruction.isBlank()) task else "SUB-AGENT INSTRUCTION:\n$instruction\n\nTASK:\n$task"
                AstraAgentRuntime(appContext).handle(prompt, localOnly = false)
            }
        }.awaitAll()
    }
}
