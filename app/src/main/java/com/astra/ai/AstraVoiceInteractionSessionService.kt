package com.astra.ai

import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

class AstraVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: android.os.Bundle?): VoiceInteractionSession = AstraVoiceInteractionSession(this)
}
