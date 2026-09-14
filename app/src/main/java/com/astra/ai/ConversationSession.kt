package com.astra.ai

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ConversationSession(private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(AssistantState.IDLE)
    val state: StateFlow<AssistantState> = _state
    private var timeoutJob: Job? = null

    fun wake() { _state.value = AssistantState.WAKE_DETECTED }
    fun listening() { _state.value = AssistantState.LISTENING }
    fun thinking() { _state.value = AssistantState.THINKING }
    fun executing() { _state.value = AssistantState.EXECUTING }
    fun speaking(timeoutMs: Long = 30_000L) {
        _state.value = AssistantState.SPEAKING
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(timeoutMs)
            end()
        }
    }
    fun interrupt() {
        timeoutJob?.cancel()
        _state.value = AssistantState.INTERRUPTED
    }
    fun end() {
        timeoutJob?.cancel()
        _state.value = AssistantState.ENDING
        _state.value = AssistantState.IDLE
    }
}
