package com.astra.ai

import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession

/** System-facing entry point for Astra when Android has selected Astra as the assistant. */
class AstraVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
    }

    override fun onLaunchVoiceAssistFromKeyguard() {
        super.onLaunchVoiceAssistFromKeyguard()
        // VoiceInteractionService is one of Android's privileged background-to-UI launch paths.
        // Show the real VoiceInteractionSession instead of starting a normal background service.
        runCatching {
            showSession(Bundle().apply { putBoolean("astra_keyguard", true) }, VoiceInteractionSession.SHOW_WITH_ASSIST)
        }
    }

    override fun onShutdown() {
        super.onShutdown()
    }
}
