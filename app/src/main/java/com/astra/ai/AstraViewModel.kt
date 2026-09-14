package com.astra.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class AssistantState { IDLE, LISTENING, THINKING, EXECUTING, SPEAKING, INTERRUPTED, ENDING }

data class AstraUiState(
    val state: AssistantState = AssistantState.IDLE,
    val transcript: String = "",
    val response: String = "",
    val localOnly: Boolean = true,
    val background: Boolean = false
)

class AstraViewModel : ViewModel() {
    private val engine: AiEngine = LocalAiEngine()
    private val _ui = MutableStateFlow(AstraUiState())
    val ui: StateFlow<AstraUiState> = _ui

    fun setLocalOnly(value: Boolean) {
        _ui.value = _ui.value.copy(localOnly = value)
    }

    fun setBackground(value: Boolean) {
        _ui.value = _ui.value.copy(background = value)
    }

    fun ask(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(
                state = AssistantState.THINKING,
                transcript = text
            )
            val answer = engine.respond(text)
            _ui.value = _ui.value.copy(
                state = AssistantState.SPEAKING,
                response = answer
            )
        }
    }

    fun idle() {
        _ui.value = _ui.value.copy(state = AssistantState.IDLE)
    }
}
