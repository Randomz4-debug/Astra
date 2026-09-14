package com.astra.ai

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class AssistantState { IDLE, WAKE_DETECTED, LISTENING, THINKING, EXECUTING, SPEAKING, INTERRUPTED, ENDING }
data class AstraUiState(val state: AssistantState = AssistantState.IDLE, val transcript: String = "", val response: String = "", val localOnly: Boolean = false, val background: Boolean = false, val notificationAccess: Boolean = false, val accessibilityAccess: Boolean = false, val networkEnabled: Boolean = true, val cloudConfigured: Boolean = false)

class AstraViewModel(private val context: Context) : ViewModel() {
    private val appContext = context.applicationContext
    private val runtime = AstraAgentRuntime(appContext)
    private val secure = SecureSettings(appContext)
    private val prefs = appContext.getSharedPreferences("astra_runtime", Context.MODE_PRIVATE)
    private val _ui = MutableStateFlow(AstraUiState(localOnly = prefs.getBoolean("local_only", false), background = prefs.getBoolean("background", false), networkEnabled = !prefs.getBoolean("local_only", false), cloudConfigured = secure.openAiApiKey() != null))
    val ui: StateFlow<AstraUiState> = _ui

    fun setLocalOnly(value: Boolean) { runCatching { prefs.edit().putBoolean("local_only", value).apply(); _ui.value = _ui.value.copy(localOnly = value, networkEnabled = !value) } }
    fun setBackground(value: Boolean) { runCatching { prefs.edit().putBoolean("background", value).apply(); _ui.value = _ui.value.copy(background = value) } }
    fun setOpenAiApiKey(value: String) { runCatching { secure.setOpenAiApiKey(value); _ui.value = _ui.value.copy(cloudConfigured = value.isNotBlank()) } }
    fun setAiMode(mode: String) { if (mode in setOf("auto", "online", "offline")) runCatching { prefs.edit().putString("ai_mode", mode).apply() } }
    fun setAlwaysListen(value: Boolean) {
        runCatching {
            prefs.edit().putBoolean("always_listen", value).apply()
            if (value) {
                prefs.edit().putBoolean("background", true).apply()
                _ui.value = _ui.value.copy(background = true)
                val intent = Intent(appContext, AstraForegroundService::class.java).setAction(AstraForegroundService.ACTION_START)
                ContextCompat.startForegroundService(appContext, intent)
            } else {
                val intent = Intent(appContext, AstraForegroundService::class.java).setAction(AstraForegroundService.ACTION_STOP)
                appContext.stopService(intent)
                _ui.value = _ui.value.copy(background = false)
            }
        }
    }
    fun setWakeWord(value: String) { runCatching { prefs.edit().putString("wake_word", value.trim().ifBlank { "astra" }).apply() } }
    fun ask(text: String) { if (text.isBlank()) return; viewModelScope.launch { val clean=text.trim(); _ui.value=_ui.value.copy(state=AssistantState.THINKING,transcript=clean,response=""); if(clean.equals("astra stop",true)||clean.equals("stop",true)||clean.equals("cancel",true)){_ui.value=_ui.value.copy(state=AssistantState.INTERRUPTED,response="Stopped.");return@launch}; val answer=runCatching{runtime.handle(clean,_ui.value.localOnly)}.getOrElse{"Astra error: ${it.message ?: "unknown error"}"}; _ui.value=_ui.value.copy(state=if(answer=="Stopped.")AssistantState.INTERRUPTED else AssistantState.SPEAKING,response=answer)} }
    companion object { fun factory(context: Context): ViewModelProvider.Factory=object:ViewModelProvider.Factory{@Suppress("UNCHECKED_CAST") override fun<T:ViewModel> create(modelClass:Class<T>):T=AstraViewModel(context.applicationContext) as T} }
}
