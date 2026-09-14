package com.astra.ai

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class AssistantState { IDLE, WAKE_DETECTED, LISTENING, THINKING, EXECUTING, SPEAKING, INTERRUPTED, ENDING }

data class AstraUiState(
    val state: AssistantState = AssistantState.IDLE,
    val transcript: String = "",
    val response: String = "",
    val localOnly: Boolean = true,
    val background: Boolean = false,
    val notificationAccess: Boolean = false,
    val accessibilityAccess: Boolean = false,
    val networkEnabled: Boolean = false
)

class AstraViewModel(private val context: Context) : ViewModel() {
    private val engine: AiEngine = LocalAiEngine()
    private val commands = LocalCommandEngine(context)
    private val memory = MemoryManager(context)
    private val _ui = MutableStateFlow(AstraUiState())
    val ui: StateFlow<AstraUiState> = _ui

    fun setLocalOnly(value: Boolean) {
        _ui.value = _ui.value.copy(localOnly = value, networkEnabled = !value)
    }

    fun setBackground(value: Boolean) { _ui.value = _ui.value.copy(background = value) }

    fun ask(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            val clean = text.trim()
            _ui.value = _ui.value.copy(state = AssistantState.THINKING, transcript = clean, response = "")

            if (clean.equals("astra stop", true) || clean.equals("stop", true) || clean.equals("cancel", true)) {
                _ui.value = _ui.value.copy(state = AssistantState.INTERRUPTED, response = "Stopped.")
                return@launch
            }

            val lower = clean.lowercase()
            if (lower.startsWith("remember that ")) {
                val body = clean.substringAfter("remember that ")
                val parts = body.split(" is ", limit = 2)
                if (parts.size == 2) {
                    memory.remember(parts[0], parts[1])
                    _ui.value = _ui.value.copy(state = AssistantState.SPEAKING, response = "I'll remember that.")
                    return@launch
                }
            }
            if (lower == "what do you remember" || lower == "show my memory") {
                val all = memory.all()
                val answer = if (all.isEmpty()) "I don't have any saved local memories." else all.entries.joinToString("; ") { "${it.key}: ${it.value}" }
                _ui.value = _ui.value.copy(state = AssistantState.SPEAKING, response = answer)
                return@launch
            }
            if (lower.startsWith("forget ")) {
                memory.forget(clean.substringAfter("forget "))
                _ui.value = _ui.value.copy(state = AssistantState.SPEAKING, response = "Forgotten from local Astra memory.")
                return@launch
            }
            if (lower == "delete all astra memory") {
                _ui.value = _ui.value.copy(state = AssistantState.EXECUTING, response = "CONFIRMATION_REQUIRED: delete all local memory")
                return@launch
            }

            val toolResult = commands.handle(clean)
            if (toolResult != null) {
                _ui.value = _ui.value.copy(state = AssistantState.EXECUTING, response = toolResult.message)
                return@launch
            }

            val answer = engine.respond(clean)
            _ui.value = _ui.value.copy(state = AssistantState.SPEAKING, response = answer)
        }
    }

    fun setState(state: AssistantState) { _ui.value = _ui.value.copy(state = state) }
    fun idle() { _ui.value = _ui.value.copy(state = AssistantState.IDLE) }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = AstraViewModel(context.applicationContext) as T
        }
    }
}
