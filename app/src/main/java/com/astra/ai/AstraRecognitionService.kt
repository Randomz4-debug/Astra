package com.astra.ai

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * RecognitionService required for Android's VoiceInteractionService qualification.
 * Astra's interactive UI uses SpeechRecognizer directly; this service exists so the
 * system can recognize Astra as a complete voice-interaction application.
 */
class AstraRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, callback: Callback?) {
        callback?.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onStopListening(callback: Callback?) {
        callback?.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onCancel(callback: Callback?) {
        callback?.error(SpeechRecognizer.ERROR_CLIENT)
    }
}
