package com.astra.ai

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.view.View
import android.widget.TextView

class AstraVoiceInteractionSession(context: android.content.Context) : VoiceInteractionSession(context) {
    override fun onCreateContentView(): View {
        val v = TextView(context)
        v.text = "Astra is ready. Say a command."
        v.textSize = 22f
        v.setPadding(48, 48, 48, 48)
        return v
    }
    override fun onHandleAssist(data: Bundle) { super.onHandleAssist(data) }
}
