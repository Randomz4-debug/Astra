package com.astra.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-local voice telemetry used only for the live UI visualizer. */
object VoiceTelemetry {
    private val _rms = MutableStateFlow(0f)
    val rms = _rms.asStateFlow()
    private val _listening = MutableStateFlow(false)
    val listening = _listening.asStateFlow()
    fun setRms(value: Float) { _rms.value = value.coerceIn(0f, 12f) }
    fun setListening(value: Boolean) { _listening.value = value }
}
