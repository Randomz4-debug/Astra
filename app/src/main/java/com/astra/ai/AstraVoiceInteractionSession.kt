package com.astra.ai

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

class AstraVoiceInteractionSession(context: android.content.Context) : VoiceInteractionSession(context) {
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private lateinit var status: TextView
    private lateinit var input: EditText

    override fun onCreateContentView(): View {
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(36, 28, 36, 28); setBackgroundColor(Color.rgb(5, 6, 10)) }
        val title = TextView(context).apply { text = "ASTRA"; textSize = 28f; setTextColor(Color.WHITE); gravity = Gravity.CENTER }
        status = TextView(context).apply { text = "Listening…"; textSize = 15f; setTextColor(Color.LTGRAY); gravity = Gravity.CENTER; setPadding(0, 12, 0, 16) }
        input = EditText(context).apply { hint = "Ask Astra…"; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); setSingleLine(false) }
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val mic = Button(context).apply { text = "🎙"; setOnClickListener { startListening() } }
        val send = Button(context).apply { text = "Send"; setOnClickListener { submit(input.text.toString()) } }
        row.addView(mic, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)); row.addView(send, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(title); root.addView(status); root.addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)); root.addView(row)
        tts = TextToSpeech(context) { if (it == TextToSpeech.SUCCESS) tts?.language = Locale.getDefault() }
        startListening(); return root
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) { status.text = "Speech recognition is unavailable"; return }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).also { r ->
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { status.text = "Listening…" }
                override fun onBeginningOfSpeech() { status.text = "Listening…" }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { status.text = "Thinking…" }
                override fun onError(error: Int) { status.text = "Tap the microphone to try again" }
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
        val runtime = AstraAgentRuntime(context)
        Thread {
            val answer = kotlinx.coroutines.runBlocking { runtime.handle(clean, false) }
            Handler(Looper.getMainLooper()).post { status.text = "Astra"; tts?.speak(answer, TextToSpeech.QUEUE_FLUSH, null, "astra-assistant"); input.setText(answer) }
        }.start()
    }

    override fun onShow(args: Bundle?, showFlags: Int) { super.onShow(args, showFlags); if (::status.isInitialized) startListening() }

    override fun onDestroy() { recognizer?.destroy(); recognizer = null; tts?.shutdown(); tts = null; super.onDestroy() }
}
