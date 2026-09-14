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
    val networkEnabled: Boolean = false,
    val cloudConfigured: Boolean = false
)

class AstraViewModel(private val context: Context) : ViewModel() {
    private val runtime = AstraAgentRuntime(context)
    private val secure = SecureSettings(context)
    private val prefs = context.getSharedPreferences("astra_runtime", Context.MODE_PRIVATE)
    private val _ui = MutableStateFlow(
        AstraUiState(
            localOnly = prefs.getBoolean("local_only", true),
            background = prefs.getBoolean("background", false),
            networkEnabled = !prefs.getBoolean("local_only", true),
            cloudConfigured = secure.openAiApiKey() != null
        )
    )
    val ui: StateFlow<AstraUiState> = _ui

    fun setLocalOnly(value: Boolean) {
        prefs.edit().putBoolean("local_only", value).apply()
        _ui.value = _ui.value.copy(localOnly = value, networkEnabled = !value)
    }
    fun setBackground(value: Boolean) {
        prefs.edit().putBoolean("background", value).apply()
        _ui.value = _ui.value.copy(background = value)
    }

    fun setOpenAiApiKey(value: String) {
        secure.setOpenAiApiKey(value)
        _ui.value = _ui.value.copy(cloudConfigured = value.isNotBlank())
    }

    fun ask(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            val clean = text.trim()
            _ui.value = _ui.value.copy(state = AssistantState.THINKING, transcript = clean, response = "")
            if (clean.equals("astra stop", true) || clean.equals("stop", true) || clean.equals("cancel", true)) {
                _ui.value = _ui.value.copy(state = AssistantState.INTERRUPTED, response = "Stopped.")
                return@launch
            }
            val answer = runCatching { runtime.handle(clean, _ui.value.localOnly) }
                .getOrElse { "Astra error: ${it.message ?: "unknown error"}" }
            _ui.value = _ui.value.copy(state = if (answer == "Stopped.") AssistantState.INTERRUPTED else AssistantState.SPEAKING, response = answer)
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
