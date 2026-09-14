package com.astra.ai

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.Color
import android.service.voice.VoiceInteractionSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

/** Real Android VoiceInteractionSession used by Astra as the global/default assistant. */
class AstraVoiceInteractionSession(private val sessionContext: Context) : VoiceInteractionSession(sessionContext) {
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: String? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var status: TextView
    private lateinit var input: EditText

    override fun onCreateContentView(): View {
        val root = LinearLayout(sessionContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 28, 36, 28)
            setBackgroundColor(Color.rgb(5, 6, 10))
        }
        val title = TextView(sessionContext).apply { text = "ASTRA"; textSize = 28f; setTextColor(Color.WHITE); gravity = Gravity.CENTER }
        status = TextView(sessionContext).apply { text = "Ready"; textSize = 15f; setTextColor(Color.LTGRAY); gravity = Gravity.CENTER; setPadding(0, 12, 0, 16) }
        input = EditText(sessionContext).apply { hint = "Ask Astra…"; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); setSingleLine(false) }
        val row = LinearLayout(sessionContext).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val mic = Button(sessionContext).apply { text = "🎙"; setOnClickListener { startListening() } }
        val send = Button(sessionContext).apply { text = "Send"; setOnClickListener { submit(input.text.toString()) } }
        row.addView(mic, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)); row.addView(send, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(title); root.addView(status); root.addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)); root.addView(row)

        tts = TextToSpeech(sessionContext) { result ->
            if (result == TextToSpeech.SUCCESS) {
                ttsReady = true
                val languageResult = tts?.setLanguage(Locale.getDefault()) ?: TextToSpeech.ERROR
                if (languageResult == TextToSpeech.LANG_MISSING_DATA || languageResult == TextToSpeech.LANG_NOT_SUPPORTED) tts?.language = Locale.US
                pendingSpeech?.let { text -> pendingSpeech = null; speak(text) }
            } else {
                ttsReady = false
                if (::status.isInitialized) status.text = "Speech output unavailable. Check the phone's TTS engine."
            }
        }
        root.post { startListening() }
        return root
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(sessionContext)) { status.text = "Speech recognition is unavailable"; return }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(sessionContext).also { r ->
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { status.text = "Listening…" }
                override fun onBeginningOfSpeech() { status.text = "Listening…" }
                override fun onRmsChanged(rmsdB: Float) { VoiceTelemetry.setRms(rmsdB) }
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { status.text = "Thinking…" }
                override fun onError(error: Int) {
                    status.text = when (error) {
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required"
                        SpeechRecognizer.ERROR_NO_MATCH -> "I didn't catch that. Tap the microphone to try again."
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer is busy. Try again."
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech service network error."
                        else -> "Speech error ($error). Tap the microphone to try again."
                    }
                }
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                    if (text.isBlank()) { status.text = "I didn't catch that. Tap the microphone to try again."; return }
                    input.setText(text); submit(text)
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                if (android.os.Build.VERSION.SDK_INT >= 34) {
                    putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                    putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, true)
                }
            }
            runCatching { r.startListening(intent) }.onFailure { status.text = "Could not start speech recognition: ${it.message ?: "unknown error"}" }
        }
    }

    private fun submit(text: String) {
        val clean = text.trim(); if (clean.isBlank()) return
        status.text = "Thinking…"
        scope.launch(Dispatchers.IO) {
            val answer = runCatching { AstraAgentRuntime(sessionContext).handle(clean, false) }.getOrElse { "Astra error: ${it.message ?: "unknown error"}" }
            launch(Dispatchers.Main) { status.text = "Astra"; input.setText(answer); speak(answer) }
        }
    }

    private fun speak(text: String) {
        if (text.isBlank()) return
        if (!ttsReady) { pendingSpeech = text; return }
        runCatching { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "astra-assistant-${System.currentTimeMillis()}") }
            .onFailure { status.text = "Could not start speech output: ${it.message ?: "unknown error"}" }
    }

    override fun onShow(args: Bundle?, showFlags: Int) { super.onShow(args, showFlags); if (::status.isInitialized) startListening() }

    override fun onHide() {
        runCatching { recognizer?.cancel() }
        super.onHide()
    }

    override fun onDestroy() {
        recognizer?.destroy(); recognizer = null; tts?.stop(); tts?.shutdown(); tts = null; ttsReady = false; pendingSpeech = null
        VoiceTelemetry.setListening(false); VoiceTelemetry.setRms(0f); scope.cancel(); super.onDestroy()
    }
}
