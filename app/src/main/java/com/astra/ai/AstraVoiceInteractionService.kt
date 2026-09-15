package com.astra.ai

import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession

/** System-facing entry point for Astra when Android has selected Astra as the assistant. */
class AstraVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        if (android.os.Build.VERSION.SDK_INT >= 36) {
            runCatching { setInvocationEffectEnabled(true) }
        }
    }

    override fun onLaunchVoiceAssistFromKeyguard() {
        super.onLaunchVoiceAssistFromKeyguard()
        runCatching {
            showSession(Bundle().apply { putBoolean("astra_keyguard", true) }, VoiceInteractionSession.SHOW_WITH_ASSIST)
        }
    }

    override fun onShutdown() {
        super.onShutdown()
    }
}
