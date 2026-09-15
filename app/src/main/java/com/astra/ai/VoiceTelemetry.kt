package com.astra.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Real-time voice/audio telemetry for Astra's UI. */
object VoiceTelemetry {
    private val _rms = MutableStateFlow(0f)
    val rms = _rms.asStateFlow()

    private val _speaking = MutableStateFlow(false)
    val speaking = _speaking.asStateFlow()

    private val _listening = MutableStateFlow(false)
    val listening = _listening.asStateFlow()

    private val _audioEnergy = MutableStateFlow(0f)
    val audioEnergy = _audioEnergy.asStateFlow()

    fun setRms(value: Float) {
        _rms.value = value.coerceIn(0f, 100f)
        _audioEnergy.value = (value / 100f).coerceIn(0f, 1f)
    }

    fun setSpeaking(value: Boolean) {
        _speaking.value = value
        if (!value) {
            _rms.value = 0f
            _audioEnergy.value = 0f
        }
    }

    fun setListening(value: Boolean) { _listening.value = value }
}
