package com.astra.ai

import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession

/** System-facing entry point for Astra when Android has selected Astra as the assistant. */
class AstraVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        // compileSdk 35 does not expose the API-36.1 method directly, so use reflection.
        if (android.os.Build.VERSION.SDK_INT >= 36) {
            runCatching {
                val method = VoiceInteractionService::class.java.getMethod("setInvocationEffectEnabled", Boolean::class.javaPrimitiveType)
                method.invoke(this, true)
            }
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
