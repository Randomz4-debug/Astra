package com.astra.ai

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Practical Android wake-word loop. It listens for the configured phrase and then
 * hands the following utterance to Astra. The recognition provider may be cloud-backed;
 * a dedicated DSP/on-device keyword model can replace this controller without changing the agent API.
 */
class AstraWakeWordController(private val context: Context) : TextToSpeech.OnInitListener {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = TextToSpeech(context, this)
    private var running = false
    private var armed = false
    private var wakeWord = "astra"
    private var language = "auto"
    private var onCommand: (String) -> Unit = {}

    override fun onInit(status: Int) { if (status == TextToSpeech.SUCCESS) tts?.language = Locale.getDefault() }

    fun start(wakeWord: String = "astra", language: String = "auto", onCommand: (String) -> Unit) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return
        this.wakeWord = wakeWord.trim().lowercase().ifBlank { "astra" }
        this.language = language.trim().ifBlank { "auto" }
        this.onCommand = onCommand
        running = true
        listen()
    }

    private fun listen() {
        if (!running) return
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) { scheduleRestart(350) }
            override fun onPartialResults(results: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                if (text.isNotBlank()) process(text)
                else scheduleRestart(250)
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (language.equals("auto", true)) Locale.getDefault().toLanguageTag() else language)
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, true)
            }
        }
        recognizer?.startListening(intent)
    }

    private fun process(text: String) {
        val lower = text.lowercase()
        if (!armed) {
            val index = lower.indexOf(wakeWord)
            if (index >= 0) {
                armed = true
                val remainder = text.substring(index + wakeWord.length).trim().trimStart(',', ':', '-', ' ')
                if (remainder.isNotBlank()) submit(remainder) else { speak("Yes?"); scheduleRestart(300) }
            } else scheduleRestart(150)
        } else {
            submit(text)
        }
    }

    private fun submit(command: String) {
        armed = false
        onCommand(command)
        scheduleRestart(500)
    }

    private fun speak(text: String) {
        if (text.isBlank()) return
        val tag = language.takeUnless { it.equals("auto", true) } ?: Locale.getDefault().toLanguageTag()
        tts?.setLanguage(Locale.forLanguageTag(tag))
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "astra-wake")
    }

    fun speakResponse(text: String, language: String = this.language) {
        this.language = language
        speak(text)
    }

    private fun scheduleRestart(delay: Long) { if (running) main.postDelayed({ listen() }, delay) }

    fun stop() {
        running = false
        armed = false
        main.removeCallbacksAndMessages(null)
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        tts?.stop()
    }

    fun release() { stop(); tts?.shutdown(); tts = null }
}
