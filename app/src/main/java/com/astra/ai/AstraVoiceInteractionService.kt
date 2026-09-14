package com.astra.ai

import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService

/** System voice-assistant entry point. When Astra is selected as the assistant, Android can invoke it from Keyguard. */
class AstraVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() { super.onReady() }

    override fun onLaunchVoiceAssistFromKeyguard() {
        super.onLaunchVoiceAssistFromKeyguard()
        runCatching {
            val i = Intent(this, AstraForegroundService::class.java).apply { action = "ASTRA_KEYGUARD_ASSIST" }
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        }
        showSession(Bundle(), 0)
    }
}
