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
import android.speech.tts.UtteranceProgressListener
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
import kotlin.math.sqrt

/**
 * The same Astra assistant surface used by the system assistant/lock-screen invocation.
 * It calls AstraAgentRuntime, so it shares the foreground app's AI provider, memory,
 * Connected Apps, screen access and command execution instead of using a second tiny brain.
 */
class AstraVoiceInteractionSession(private val sessionContext: Context) : VoiceInteractionSession(sessionContext) {
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: String? = null
    private var destroyed = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var root: FrameLayout
    private lateinit var orb: AstraOrbView
    private lateinit var wave: SiriWaveView
    private lateinit var status: TextView
    private lateinit var transcript: TextView
    private lateinit var answer: TextView
    private lateinit var textInput: EditText

    override fun onCreateContentView(): View {
        val metrics = sessionContext.resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels
        val cardHeight = (screenH * 0.38f).toInt().coerceAtLeast(dp(300))
        val orbSize = (screenW * 0.44f).toInt().coerceIn(dp(150), dp(360))

        root = FrameLayout(sessionContext).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(10), 0, dp(10), dp(10))
        }

        val card = LinearLayout(sessionContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), dp(12), dp(18), dp(14))
            background = roundedBackground(Color.argb(78, 255, 255, 255), dp(28).toFloat())
            elevation = dp(8).toFloat()
        }

        val titleRow = LinearLayout(sessionContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(sessionContext).apply {
            text = "Astra"
            textSize = 20f
            setTextColor(Color.rgb(22, 24, 30))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val brain = TextView(sessionContext).apply {
            text = "  •  FULL ASTRA BRAIN"
            textSize = 10f
            setTextColor(Color.rgb(70, 74, 84))
        }
        titleRow.addView(title, LinearLayout.LayoutParams(0, dp(30), 1f))
        titleRow.addView(brain, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30)))

        status = TextView(sessionContext).apply {
            text = "Listening…"
            textSize = 12f
            setTextColor(Color.rgb(75, 79, 90))
            gravity = Gravity.CENTER
        }

        orb = AstraOrbView(sessionContext)
        wave = SiriWaveView(sessionContext)
        transcript = TextView(sessionContext).apply {
            text = ""
            textSize = 14f
            setTextColor(Color.rgb(25, 27, 33))
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        answer = TextView(sessionContext).apply {
            text = ""
            textSize = 15f
            setTextColor(Color.rgb(20, 22, 28))
            gravity = Gravity.CENTER
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            visibility = View.GONE
        }
        textInput = EditText(sessionContext).apply {
            hint = "Type to Astra…"
            setHintTextColor(Color.rgb(90, 94, 104))
            setTextColor(Color.rgb(20, 22, 28))
            textSize = 15f
            setSingleLine(false)
            maxLines = 2
            background = roundedBackground(Color.argb(125, 170, 174, 184), dp(22).toFloat())
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }

        val controls = LinearLayout(sessionContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val mic = ImageButton(sessionContext).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            setColorFilter(Color.rgb(30, 32, 38))
            background = roundedBackground(Color.argb(115, 255, 255, 255), 100f)
            contentDescription = "Talk to Astra"
            setOnClickListener { if (recognizer != null) stopListening() else startListening() }
        }
        val send = ImageButton(sessionContext).apply {
            setImageResource(android.R.drawable.ic_media_play)
            setColorFilter(Color.rgb(30, 32, 38))
            background = roundedBackground(Color.argb(150, 255, 255, 255), 100f)
            contentDescription = "Send to Astra"
            setOnClickListener { submit(textInput.text.toString()) }
        }
        controls.addView(mic, LinearLayout.LayoutParams(dp(54), dp(54)).apply { marginEnd = dp(10) })
        controls.addView(send, LinearLayout.LayoutParams(dp(54), dp(54)))

        card.addView(titleRow, LinearLayout.LayoutParams(-1, dp(30)))
        card.addView(status, LinearLayout.LayoutParams(-1, dp(22)))
        card.addView(orb, LinearLayout.LayoutParams(orbSize, orbSize).apply { gravity = Gravity.CENTER_HORIZONTAL })
        card.addView(wave, LinearLayout.LayoutParams(-1, dp(38)))
        card.addView(transcript, LinearLayout.LayoutParams(-1, dp(34)))
        card.addView(answer, LinearLayout.LayoutParams(-1, dp(46)))
        card.addView(textInput, LinearLayout.LayoutParams(-1, dp(64)).apply { topMargin = dp(5) })
        card.addView(controls, LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(6) })

        root.addView(card, FrameLayout.LayoutParams(-1, cardHeight, Gravity.BOTTOM))

        tts = TextToSpeech(sessionContext) { result ->
            if (destroyed) return@TextToSpeech
            ttsReady = result == TextToSpeech.SUCCESS
            if (ttsReady) {
                val r = tts?.setLanguage(Locale.getDefault()) ?: TextToSpeech.ERROR
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) tts?.language = Locale.US
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { orb.setSpeaking(true); wave.setSpeaking(true); VoiceTelemetry.setSpeaking(true) }
                    override fun onDone(utteranceId: String?) { orb.setSpeaking(false); wave.setSpeaking(false); VoiceTelemetry.setSpeaking(false); VoiceTelemetry.setRms(0f) }
                    override fun onError(utteranceId: String?) { orb.setSpeaking(false); wave.setSpeaking(false); VoiceTelemetry.setSpeaking(false) }
                    override fun onAudioAvailable(utteranceId: String?, audio: ByteArray?) {
                        if (audio == null || audio.isEmpty()) return
                        val rms = pcm16Rms(audio)
                        orb.setRms(rms)
                        wave.setRms(rms)
                        VoiceTelemetry.setRms(rms)
                    }
                })
                pendingSpeech?.let { pending -> pendingSpeech = null; speak(pending) }
            }
        }

        root.post { startListening() }
        return root
    }

    private fun startListening() {
        if (destroyed) return
        if (!SpeechRecognizer.isRecognitionAvailable(sessionContext)) {
            status.text = "Speech recognition unavailable"
            orb.setListening(false); wave.setListening(false)
            return
        }
        recognizer?.destroy()
        VoiceTelemetry.setListening(true); orb.setListening(true); wave.setListening(true)
        status.text = "Listening…"; transcript.text = ""; answer.visibility = View.GONE
        recognizer = SpeechRecognizer.createSpeechRecognizer(sessionContext).also { r ->
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { status.text = "Listening…" }
                override fun onBeginningOfSpeech() { status.text = "Listening…" }
                override fun onRmsChanged(rmsdB: Float) { VoiceTelemetry.setRms(rmsdB); orb.setRms(rmsdB); wave.setRms(rmsdB) }
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { VoiceTelemetry.setListening(false); orb.setListening(false); wave.setListening(false); status.text = "Thinking…" }
                override fun onError(error: Int) { VoiceTelemetry.setListening(false); orb.setListening(false); wave.setListening(false); recognizer = null; status.text = when (error) { SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required"; SpeechRecognizer.ERROR_NO_MATCH -> "I didn't catch that"; SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer is busy"; else -> "Speech error ($error)" } }
                override fun onResults(results: Bundle?) {
                    VoiceTelemetry.setListening(false); orb.setListening(false); wave.setListening(false)
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                    if (text.isBlank()) { status.text = "I didn't catch that"; return }
                    transcript.text = text; submit(text)
                }
                override fun onPartialResults(partialResults: Bundle?) { val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty(); if (partial.isNotBlank()) transcript.text = partial }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                if (android.os.Build.VERSION.SDK_INT >= 34) {
                    putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                    putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, true)
                }
            }
            runCatching { r.startListening(intent) }.onFailure { VoiceTelemetry.setListening(false); orb.setListening(false); wave.setListening(false); status.text = "Could not start listening" }
        }
    }

    private fun stopListening() {
        runCatching { recognizer?.cancel() }
        recognizer?.destroy(); recognizer = null
        VoiceTelemetry.setListening(false); orb.setListening(false); wave.setListening(false); status.text = "Ready"
    }

    private fun submit(text: String) {
        val clean = text.trim(); if (clean.isBlank() || destroyed) return
        transcript.text = clean; status.text = "Thinking…"; answer.visibility = View.GONE
        scope.launch(Dispatchers.IO) {
            val result = runCatching { AstraAgentRuntime(sessionContext).handle(clean, false) }
                .getOrElse { "Astra error: ${it.message ?: "unknown error"}" }
            launch(Dispatchers.Main) {
                if (destroyed) return@launch
                answer.text = result; answer.visibility = View.VISIBLE; textInput.setText(""); status.text = "Astra"; speak(result)
            }
        }
    }

    private fun speak(text: String) {
        if (text.isBlank() || destroyed) return
        if (!ttsReady) { pendingSpeech = text; return }
        orb.setSpeaking(true); wave.setSpeaking(true); VoiceTelemetry.setSpeaking(true)
        runCatching {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "astra-assistant-${System.currentTimeMillis()}")
        }.onFailure {
            orb.setSpeaking(false); wave.setSpeaking(false); VoiceTelemetry.setSpeaking(false); status.text = "Speech output unavailable"
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) { super.onShow(args, showFlags); root.post { startListening() } }
    override fun onHide() { stopListening(); super.onHide() }
    override fun onDestroy() { destroyed = true; stopListening(); tts?.stop(); tts?.shutdown(); tts = null; ttsReady = false; pendingSpeech = null; VoiceTelemetry.setSpeaking(false); VoiceTelemetry.setRms(0f); scope.cancel(); super.onDestroy() }

    private fun dp(value: Int): Int = (value * sessionContext.resources.displayMetrics.density).toInt().coerceAtLeast(1)

    private fun pcm16Rms(bytes: ByteArray): Float {
        var sum = 0.0
        var count = 0
        var i = 0
        while (i + 1 < bytes.size) {
            val sample = ((bytes[i + 1].toInt() shl 8) or (bytes[i].toInt() and 0xff)).toShort().toInt()
            sum += sample.toDouble() * sample.toDouble()
            count++
            i += 2
        }
        return if (count == 0) 0f else (sqrt(sum / count) / 32768.0 * 100.0).toFloat()
    }

    private fun roundedBackground(color: Int, radius: Float): android.graphics.drawable.GradientDrawable = android.graphics.drawable.GradientDrawable().apply { setColor(color); cornerRadius = radius }
}

/** Large, sound-reactive Astra orb used for the background/lock-screen assistant surface. */
private class AstraOrbView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var rms = 0f
    private var listening = false
    private var speaking = false
    private var phase = 0f
    init { isFocusable = false; postInvalidateOnAnimation() }
    fun setRms(value: Float) { rms = value; invalidate() }
    fun setListening(value: Boolean) { listening = value; invalidate() }
    fun setSpeaking(value: Boolean) { speaking = value; invalidate() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        phase += 0.045f
        val w = width.toFloat(); val h = height.toFloat(); val cx = w / 2f; val cy = h / 2f
        val energy = ((abs(rms) / 12f).coerceIn(0.08f, 1.6f))
        val pulse = 1f + (if (listening || speaking) energy * .12f else .025f) + kotlin.math.sin(phase) * .02f
        val radius = (minOf(w, h) * .36f * pulse)
        for (ring in 4 downTo 1) {
            val alpha = (12 + ring * 10 + (energy * 18).toInt()).coerceIn(10, 90)
            paint.color = Color.argb(alpha, 40 + ring * 25, 95 + ring * 20, 255)
            canvas.drawCircle(cx, cy, radius + ring * minOf(w, h) * .045f, paint)
        }
        paint.color = Color.argb(235, 30, 95, 245)
        canvas.drawCircle(cx, cy, radius, paint)
        paint.color = Color.argb(180, 130, 220, 255)
        canvas.drawCircle(cx - radius * .18f, cy - radius * .22f, radius * .58f, paint)
        paint.color = Color.argb(225, 255, 255, 255)
        canvas.drawCircle(cx - radius * .23f, cy - radius * .27f, radius * .16f, paint)
        postInvalidateOnAnimation()
    }
}

/** Compact Siri-like waveform that reacts to microphone and synthesized-audio telemetry. */
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
        super.onDraw(canvas); phase += 0.055f; val w = width.toFloat(); val h = height.toFloat(); val cy = h / 2f
        rect.set(0f, 0f, w, h)
        paint.style = Paint.Style.FILL; paint.color = Color.argb(35, 30, 100, 245); canvas.drawRoundRect(rect, 30f, 30f, paint)
        val energy = if (listening || speaking) (abs(rms) / 12f).coerceIn(0.12f, 1.35f) else 0.16f; val bars = 43
        for (i in 0 until bars) {
            val x = (i + .5f) * w / bars; val distance = abs(i - bars / 2f) / (bars / 2f); val envelope = (1f - distance * .82f).coerceAtLeast(.08f)
            val pulse = (.25f + .75f * abs(kotlin.math.sin(phase * 2.0 + i * .52))).toFloat(); val amp = h * .34f * envelope * (0.35f + energy * .85f) * pulse
            paint.color = colors[i * colors.size / bars]; canvas.drawRoundRect(x - 1.8f, cy - amp, x + 1.8f, cy + amp, 4f, 4f, paint)
        }
        postInvalidateOnAnimation()
    }
}
