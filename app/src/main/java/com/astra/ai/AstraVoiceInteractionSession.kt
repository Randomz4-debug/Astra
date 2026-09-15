package com.astra.ai

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.service.voice.VoiceInteractionSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sin

/** Siri-like assistant surface. This is a VoiceInteractionSession, not an Activity. */
class AstraVoiceInteractionSession(private val sessionContext: Context) : VoiceInteractionSession(sessionContext) {
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: String? = null
    private var destroyed = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var root: FrameLayout
    private lateinit var wave: SiriWaveView
    private lateinit var status: TextView
    private lateinit var transcript: TextView
    private lateinit var answer: TextView
    private lateinit var textInput: EditText

    override fun onCreateContentView(): View {
        root = FrameLayout(sessionContext).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(18, 0, 18, 18)
        }
        val card = LinearLayout(sessionContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(22, 18, 22, 18)
            background = roundedBackground(Color.argb(238, 8, 10, 16), 34f)
        }
        val title = TextView(sessionContext).apply { text = "Astra"; textSize = 20f; setTextColor(Color.WHITE); gravity = Gravity.CENTER }
        status = TextView(sessionContext).apply { text = "Listening…"; textSize = 12f; setTextColor(Color.rgb(170, 180, 200)); gravity = Gravity.CENTER }
        wave = SiriWaveView(sessionContext)
        transcript = TextView(sessionContext).apply { text = ""; textSize = 15f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; maxLines = 3 }
        answer = TextView(sessionContext).apply { text = ""; textSize = 16f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; maxLines = 6; visibility = View.GONE }
        textInput = EditText(sessionContext).apply {
            hint = "Type to Astra…"
            setHintTextColor(Color.rgb(125, 130, 145)); setTextColor(Color.WHITE); textSize = 15f
            setSingleLine(false); maxLines = 3; background = roundedBackground(Color.rgb(24, 27, 35), 24f); setPadding(18, 10, 18, 10)
        }
        val controls = LinearLayout(sessionContext).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val mic = ImageButton(sessionContext).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now); setColorFilter(Color.WHITE); background = roundedBackground(Color.rgb(32, 35, 44), 100f)
            contentDescription = "Talk to Astra"; setOnClickListener { if (recognizer != null) stopListening() else startListening() }
        }
        val send = ImageButton(sessionContext).apply {
            setImageResource(android.R.drawable.ic_media_play); setColorFilter(Color.WHITE); background = roundedBackground(Color.rgb(35, 70, 110), 100f)
            contentDescription = "Send to Astra"; setOnClickListener { submit(textInput.text.toString()) }
        }
        controls.addView(mic, LinearLayout.LayoutParams(58, 58).apply { marginEnd = 10 }); controls.addView(send, LinearLayout.LayoutParams(58, 58))
        card.addView(title, LinearLayout.LayoutParams(-1, 30)); card.addView(status, LinearLayout.LayoutParams(-1, 24)); card.addView(wave, LinearLayout.LayoutParams(-1, 76))
        card.addView(transcript, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 3 })
        card.addView(answer, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 6 })
        card.addView(textInput, LinearLayout.LayoutParams(-1, 52).apply { topMargin = 8 }); card.addView(controls, LinearLayout.LayoutParams(-1, 62).apply { topMargin = 8 })
        root.addView(card, FrameLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        tts = TextToSpeech(sessionContext) { result ->
            if (destroyed) return@TextToSpeech
            ttsReady = result == TextToSpeech.SUCCESS
            if (ttsReady) {
                val r = tts?.setLanguage(Locale.getDefault()) ?: TextToSpeech.ERROR
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) tts?.language = Locale.US
                pendingSpeech?.let { pending -> pendingSpeech = null; speak(pending) }
            }
        }
        root.post { startListening() }
        return root
    }

    private fun startListening() {
        if (destroyed) return
        if (!SpeechRecognizer.isRecognitionAvailable(sessionContext)) { status.text = "Speech recognition unavailable"; wave.setListening(false); return }
        recognizer?.destroy(); VoiceTelemetry.setListening(true); wave.setListening(true); status.text = "Listening…"; transcript.text = ""; answer.visibility = View.GONE
        recognizer = SpeechRecognizer.createSpeechRecognizer(sessionContext).also { r ->
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { status.text = "Listening…" }
                override fun onBeginningOfSpeech() { status.text = "Listening…" }
                override fun onRmsChanged(rmsdB: Float) { VoiceTelemetry.setRms(rmsdB); wave.setRms(rmsdB) }
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { VoiceTelemetry.setListening(false); wave.setListening(false); status.text = "Thinking…" }
                override fun onError(error: Int) { VoiceTelemetry.setListening(false); wave.setListening(false); recognizer = null; status.text = when (error) { SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required"; SpeechRecognizer.ERROR_NO_MATCH -> "I didn't catch that"; SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer is busy"; else -> "Speech error ($error)" } }
                override fun onResults(results: Bundle?) {
                    VoiceTelemetry.setListening(false); wave.setListening(false)
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                    if (text.isBlank()) { status.text = "I didn't catch that"; return }
                    transcript.text = text; submit(text)
                }
                override fun onPartialResults(partialResults: Bundle?) { val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty(); if (partial.isNotBlank()) transcript.text = partial }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM); putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true); putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3); putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                if (android.os.Build.VERSION.SDK_INT >= 34) { putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true); putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, true) }
            }
            runCatching { r.startListening(intent) }.onFailure { VoiceTelemetry.setListening(false); wave.setListening(false); status.text = "Could not start listening" }
        }
    }

    private fun stopListening() { runCatching { recognizer?.cancel() }; recognizer?.destroy(); recognizer = null; VoiceTelemetry.setListening(false); wave.setListening(false); status.text = "Ready" }

    private fun submit(text: String) {
        val clean = text.trim(); if (clean.isBlank() || destroyed) return
        transcript.text = clean; status.text = "Thinking…"; answer.visibility = View.GONE
        scope.launch(Dispatchers.IO) {
            val result = runCatching { AstraAgentRuntime(sessionContext).handle(clean, false) }.getOrElse { "Astra error: ${it.message ?: "unknown error"}" }
            launch(Dispatchers.Main) {
                if (destroyed) return@launch
                answer.text = result; answer.visibility = View.VISIBLE; textInput.setText(""); status.text = "Astra"; speak(result)
            }
        }
    }

    private fun speak(text: String) {
        if (text.isBlank() || destroyed) return
        if (!ttsReady) { pendingSpeech = text; return }
        wave.setSpeaking(true); VoiceTelemetry.setSpeaking(true)
        runCatching { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "astra-assistant-${System.currentTimeMillis()}") }.onFailure { wave.setSpeaking(false); VoiceTelemetry.setSpeaking(false); status.text = "Speech output unavailable" }
    }

    override fun onShow(args: Bundle?, showFlags: Int) { super.onShow(args, showFlags); root.post { startListening() } }
    override fun onHide() { stopListening(); super.onHide() }
    override fun onDestroy() { destroyed = true; stopListening(); tts?.stop(); tts?.shutdown(); tts = null; ttsReady = false; pendingSpeech = null; VoiceTelemetry.setSpeaking(false); VoiceTelemetry.setRms(0f); scope.cancel(); super.onDestroy() }
    private fun roundedBackground(color: Int, radius: Float): android.graphics.drawable.GradientDrawable = android.graphics.drawable.GradientDrawable().apply { setColor(color); cornerRadius = radius }
}

/** Siri-like reactive waveform drawn in the VoiceInteractionSession itself. */
private class SiriWaveView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var rms = 0f; private var listening = false; private var speaking = false; private var phase = 0f
    private val colors = intArrayOf(Color.rgb(40, 100, 255), Color.rgb(80, 205, 255), Color.WHITE, Color.rgb(90, 180, 255), Color.rgb(40, 100, 255))
    init { isFocusable = false; postInvalidateOnAnimation() }
    fun setRms(value: Float) { rms = value; invalidate() }
    fun setListening(value: Boolean) { listening = value; invalidate() }
    fun setSpeaking(value: Boolean) { speaking = value; invalidate() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas); phase += 0.055f; val w = width.toFloat(); val h = height.toFloat(); val cy = h / 2f; rect.set(0f, 0f, w, h)
        paint.style = Paint.Style.FILL; paint.color = Color.argb(45, 40, 110, 255); canvas.drawRoundRect(rect, 30f, 30f, paint)
        val energy = if (listening || speaking) (abs(rms) / 12f).coerceIn(0.12f, 1.35f) else 0.16f; val bars = 43
        for (i in 0 until bars) {
            val x = (i + .5f) * w / bars; val distance = abs(i - bars / 2f) / (bars / 2f); val envelope = (1f - distance * .82f).coerceAtLeast(.08f)
            val pulse = (.25f + .75f * abs(sin(phase * 2.0 + i * .52))).toFloat(); val amp = h * .34f * envelope * (0.35f + energy * .85f) * pulse
            paint.color = colors[i * colors.size / bars]; canvas.drawRoundRect(x - 1.8f, cy - amp, x + 1.8f, cy + amp, 4f, 4f, paint)
        }
        postInvalidateOnAnimation()
    }
}
