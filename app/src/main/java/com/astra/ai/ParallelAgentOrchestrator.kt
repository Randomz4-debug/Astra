package com.astra.ai

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Runs independent sub-agent prompts concurrently and returns results in input order. */
class ParallelAgentOrchestrator(context: Context) {
    private val appContext = context.applicationContext

    suspend fun run(tasks: List<String>): List<String> = coroutineScope {
        tasks.map { task ->
            async { AstraAgentRuntime(appContext).handle(task, localOnly = false) }
        }.awaitAll()
    }
}
