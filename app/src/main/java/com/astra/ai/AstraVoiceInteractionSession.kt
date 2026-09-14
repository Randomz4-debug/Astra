package com.astra.ai

import android.content.Context
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

/** System-assistant voice session used when Astra is selected as Android's default assistant. */
class AstraVoiceInteractionSession(private val sessionContext: Context) : VoiceInteractionSession(sessionContext) {
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: String? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var status: TextView
    private lateinit var input: EditText

    override fun onCreateContentView(): View {
        val root = LinearLayout(sessionContext).apply { orientation = LinearLayout.VERTICAL; setPadding(36, 28, 36, 28); setBackgroundColor(Color.rgb(5, 6, 10)) }
        val title = TextView(sessionContext).apply { text = "ASTRA"; textSize = 28f; setTextColor(Color.WHITE); gravity = Gravity.CENTER }
        status = TextView(sessionContext).apply { text = "Listening…"; textSize = 15f; setTextColor(Color.LTGRAY); gravity = Gravity.CENTER; setPadding(0, 12, 0, 16) }
        input = EditText(sessionContext).apply { hint = "Ask Astra…"; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); setSingleLine(false) }
        val row = LinearLayout(sessionContext).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val mic = Button(sessionContext).apply { text = "🎙"; setOnClickListener { startListening() } }
        val send = Button(sessionContext).apply { text = "Send"; setOnClickListener { submit(input.text.toString()) } }
        row.addView(mic, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)); row.addView(send, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(title); root.addView(status); root.addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)); root.addView(row)

        tts = TextToSpeech(sessionContext) { result ->
            if (result == TextToSpeech.SUCCESS) {
                ttsReady = true
                tts?.language = Locale.getDefault()
                pendingSpeech?.let { text -> pendingSpeech = null; speak(text) }
            } else {
                ttsReady = false
                if (::status.isInitialized) status.text = "Speech output unavailable — check the phone's Text-to-speech engine."
            }
        }
        startListening()
        return root
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(sessionContext)) { status.text = "Speech recognition is unavailable"; return }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(sessionContext).also { r ->
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { status.text = "Listening…" }
                override fun onBeginningOfSpeech() { status.text = "Listening…" }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { status.text = "Thinking…" }
                override fun onError(error: Int) { status.text = "Speech error ($error). Tap the microphone to try again." }
                override fun onResults(results: Bundle?) { results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { input.setText(it); submit(it) } }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply { putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM); putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false); putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3) })
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
        runCatching { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "astra-assistant") }
    }

    override fun onShow(args: Bundle?, showFlags: Int) { super.onShow(args, showFlags); if (::status.isInitialized) startListening() }

    override fun onDestroy() {
        recognizer?.destroy(); recognizer = null; tts?.stop(); tts?.shutdown(); tts = null; ttsReady = false; pendingSpeech = null; scope.cancel(); super.onDestroy()
    }
}
