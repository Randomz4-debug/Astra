from pathlib import Path

p = Path('app/src/main/java/com/astra/ai/AstraVoiceInteractionSession.kt')
source = r'''package com.astra.ai

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Full Astra voice surface used by the Android assistant session.
 * It talks directly to AstraAgentRuntime, so background/lock-screen invocation uses the same
 * chat history, memory, AI routing, tools and connected-app state as the main Astra app.
 */
class AstraVoiceInteractionSession(private val sessionContext: Context) : VoiceInteractionSession(sessionContext) {
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: String? = null
    private var destroyed = false
    private var expanded = true
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var root: FrameLayout
    private lateinit var orb: SiriOrbView
    private lateinit var panel: LinearLayout
    private lateinit var status: TextView
    private lateinit var transcript: TextView
    private lateinit var answer: TextView
    private lateinit var input: EditText

    private fun assistantName(): String = sessionContext.applicationContext
        .getSharedPreferences("astra_runtime", Context.MODE_PRIVATE)
        .getString("assistant_name", "Astra")?.trim()?.ifBlank { "Astra" } ?: "Astra"

    override fun onCreateContentView(): View {
        val name = assistantName()
        root = FrameLayout(sessionContext).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(12, 0, 12, 18)
        }

        orb = SiriOrbView(sessionContext).apply {
            contentDescription = "$name assistant orb"
            setOnClickListener {
                expanded = !expanded
                panel.visibility = if (expanded) View.VISIBLE else View.GONE
                orb.visibility = if (expanded) View.GONE else View.VISIBLE
                if (expanded) input.requestFocus()
            }
            setOnLongClickListener { stopEverything(); true }
        }

        panel = LinearLayout(sessionContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(24, 20, 24, 20)
            background = rounded(Color.argb(205, 248, 249, 252), 34f, Color.argb(80, 255, 255, 255))
        }

        val header = LinearLayout(sessionContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(sessionContext).apply {
            text = name
            textSize = 23f
            setTextColor(Color.rgb(20, 22, 28))
        }
        status = TextView(sessionContext).apply {
            text = "Ready"
            textSize = 13f
            setTextColor(Color.rgb(80, 84, 96))
            gravity = Gravity.RIGHT
        }
        header.addView(title, LinearLayout.LayoutParams(0, 42).apply { weight = 1f })
        header.addView(status, LinearLayout.LayoutParams(130, 42))

        val wave = SiriWaveView(sessionContext)
        orb.bindWave(wave)
        transcript = TextView(sessionContext).apply {
            textSize = 16f
            setTextColor(Color.rgb(25, 27, 34))
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 3
        }
        answer = TextView(sessionContext).apply {
            textSize = 16f
            setTextColor(Color.rgb(25, 27, 34))
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 6
            visibility = View.GONE
        }
        input = EditText(sessionContext).apply {
            hint = "Message or command"
            textSize = 16f
            setTextColor(Color.rgb(20, 22, 28))
            setHintTextColor(Color.rgb(105, 109, 120))
            setSingleLine(false)
            maxLines = 4
            background = rounded(Color.argb(112, 100, 105, 116), 24f, Color.argb(75, 255, 255, 255))
            setPadding(20, 12, 20, 12)
        }

        val controls = LinearLayout(sessionContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val mic = ImageButton(sessionContext).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            setColorFilter(Color.rgb(25, 28, 36))
            background = rounded(Color.argb(95, 255, 255, 255), 100f, Color.argb(90, 255, 255, 255))
            contentDescription = "Talk to $name"
            setOnClickListener { if (recognizer != null) stopListening() else startListening() }
        }
        val send = ImageButton(sessionContext).apply {
            setImageResource(android.R.drawable.ic_menu_send)
            setColorFilter(Color.WHITE)
            background = rounded(Color.rgb(42, 92, 210), 100f, Color.argb(110, 255, 255, 255))
            contentDescription = "Send to $name"
            setOnClickListener { submit(input.text.toString()) }
        }
        controls.addView(mic, LinearLayout.LayoutParams(58, 58).apply { marginEnd = 12 })
        controls.addView(send, LinearLayout.LayoutParams(58, 58))

        panel.addView(header, LinearLayout.LayoutParams(-1, 42))
        panel.addView(wave, LinearLayout.LayoutParams(-1, 74).apply { topMargin = 4 })
        panel.addView(transcript, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 3 })
        panel.addView(answer, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 5 })
        panel.addView(input, LinearLayout.LayoutParams(-1, 70).apply { topMargin = 10 })
        panel.addView(controls, LinearLayout.LayoutParams(-1, 64).apply { topMargin = 9 })

        root.addView(panel, FrameLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        root.addView(orb, FrameLayout.LayoutParams(120, 120, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = 20 })

        root.post {
            val width = root.width.coerceAtLeast(1)
            val height = root.height.coerceAtLeast(sessionContext.resources.displayMetrics.heightPixels)
            // Expanded surface is deliberately large: about 36% of the screen height.
            val panelHeight = (height * 0.36f).toInt().coerceIn(390, (height * 0.48f).toInt())
            panel.layoutParams = FrameLayout.LayoutParams(-1, panelHeight, Gravity.BOTTOM).apply { bottomMargin = 8 }
            // Collapsed orb is roughly 44% of screen width and remains large without covering the
            // entire lock screen. The panel is the expanded interaction surface.
            val orbSize = (width * 0.44f).toInt().coerceIn(180, (height * 0.32f).toInt())
            orb.layoutParams = FrameLayout.LayoutParams(orbSize, orbSize, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = 26 }
            panel.visibility = View.VISIBLE
            orb.visibility = View.GONE
        }

        tts = TextToSpeech(sessionContext) { result ->
            if (destroyed) return@TextToSpeech
            ttsReady = result == TextToSpeech.SUCCESS
            if (ttsReady) {
                val r = tts?.setLanguage(Locale.getDefault()) ?: TextToSpeech.ERROR
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) tts?.language = Locale.US
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) { root.post { orb.setSpeaking(true); VoiceTelemetry.setSpeaking(true); status.text = "Speaking" } }
                    override fun onAudioAvailable(id: String?, audio: ByteArray?) {
                        if (audio == null || audio.size < 2) return
                        val b = ByteBuffer.wrap(audio).order(ByteOrder.LITTLE_ENDIAN)
                        var sum = 0.0; var count = 0
                        while (b.remaining() >= 2) { val s = b.short.toDouble(); sum += s * s; count++ }
                        if (count > 0) {
                            val rms = sqrt(sum / count).toFloat()
                            val db = 20f * log10((rms / 32768f).coerceAtLeast(.00001f))
                            root.post { orb.setRms(db); VoiceTelemetry.setRms(db) }
                        }
                    }
                    override fun onDone(id: String?) { root.post { orb.setSpeaking(false); VoiceTelemetry.setSpeaking(false); status.text = "Ready" } }
                    override fun onError(id: String?) { root.post { orb.setSpeaking(false); VoiceTelemetry.setSpeaking(false); status.text = "Speech output unavailable" } }
                })
                pendingSpeech?.let { pendingSpeech = null; speak(it) }
            }
        }

        root.post { startListening() }
        return root
    }

    private fun startListening() {
        if (destroyed || !SpeechRecognizer.isRecognitionAvailable(sessionContext)) {
            status.text = "Speech recognition unavailable"; return
        }
        recognizer?.destroy()
        VoiceTelemetry.setListening(true); orb.setListening(true); status.text = "Listening…"; transcript.text = ""; answer.visibility = View.GONE
        recognizer = SpeechRecognizer.createSpeechRecognizer(sessionContext).also { r ->
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) { status.text = "Listening…" }
                override fun onBeginningOfSpeech() { status.text = "Listening…" }
                override fun onRmsChanged(v: Float) { VoiceTelemetry.setRms(v); orb.setRms(v) }
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() { VoiceTelemetry.setListening(false); orb.setListening(false); status.text = "Thinking…" }
                override fun onError(e: Int) { VoiceTelemetry.setListening(false); orb.setListening(false); recognizer = null; status.text = when (e) { SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required"; SpeechRecognizer.ERROR_NO_MATCH -> "I didn't catch that"; SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer is busy"; else -> "Speech error ($e)" } }
                override fun onResults(results: Bundle?) {
                    VoiceTelemetry.setListening(false); orb.setListening(false)
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                    if (text.isBlank()) status.text = "I didn't catch that" else { transcript.text = text; submit(text) }
                }
                override fun onPartialResults(results: Bundle?) { results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { transcript.text = it } }
                override fun onEvent(type: Int, params: Bundle?) {}
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
            runCatching { r.startListening(intent) }.onFailure { VoiceTelemetry.setListening(false); orb.setListening(false); status.text = "Could not start listening" }
        }
    }

    private fun stopListening() {
        runCatching { recognizer?.cancel() }; recognizer?.destroy(); recognizer = null
        VoiceTelemetry.setListening(false); orb.setListening(false); status.text = "Ready"
    }

    private fun submit(text: String) {
        val clean = text.trim(); if (clean.isBlank() || destroyed) return
        transcript.text = clean; status.text = "Thinking…"; answer.visibility = View.GONE
        scope.launch(Dispatchers.IO) {
            val result = runCatching { AstraAgentRuntime(sessionContext).handle(clean, false) }
                .getOrElse { "Astra error: ${it.message ?: "unknown error"}" }
            launch(Dispatchers.Main) {
                if (!destroyed) {
                    answer.text = result
                    answer.visibility = View.VISIBLE
                    input.setText("")
                    status.text = assistantName()
                    speak(result)
                }
            }
        }
    }

    private fun speak(text: String) {
        if (text.isBlank() || destroyed) return
        if (!ttsReady) { pendingSpeech = text; return }
        orb.setSpeaking(true); VoiceTelemetry.setSpeaking(true)
        runCatching { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "astra-${System.currentTimeMillis()}") }
            .onFailure { orb.setSpeaking(false); VoiceTelemetry.setSpeaking(false); status.text = "Speech output unavailable" }
    }

    private fun stopEverything() {
        stopListening(); runCatching { tts?.stop() }; orb.setSpeaking(false); VoiceTelemetry.setSpeaking(false); status.text = "Stopped"
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        root.post { if (!destroyed) startListening() }
    }

    override fun onHide() { stopEverything(); super.onHide() }

    override fun onDestroy() {
        destroyed = true; stopEverything(); tts?.shutdown(); tts = null; ttsReady = false; pendingSpeech = null
        scope.cancel(); super.onDestroy()
    }

    private fun rounded(color: Int, radius: Float, stroke: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius; setStroke(1, stroke)
    }
}

private class SiriOrbView(context: Context) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var rms = 0f; private var listening = false; private var speaking = false; private var phase = 0f; private var wave: SiriWaveView? = null
    fun bindWave(v: SiriWaveView) { wave = v }
    fun setRms(v: Float) { rms = v; wave?.setRms(v); invalidate() }
    fun setListening(v: Boolean) { listening = v; wave?.setListening(v); invalidate() }
    fun setSpeaking(v: Boolean) { speaking = v; wave?.setSpeaking(v); invalidate() }
    override fun onDraw(c: Canvas) {
        phase += .075f
        val cx = width / 2f; val cy = height / 2f
        val e = if (listening || speaking) (abs(rms) / 14f).coerceIn(.12f, 1.5f) else .16f
        val pulse = 1f + .06f * sin(phase * 2f).toFloat() + .13f * e
        p.style = Paint.Style.FILL
        p.color = Color.argb(30, 70, 150, 255); c.drawCircle(cx, cy, width * .47f * pulse, p)
        p.color = Color.argb(80, 60, 135, 255); c.drawCircle(cx, cy, width * .40f * pulse, p)
        p.color = Color.rgb(42, 94, 220); c.drawCircle(cx, cy, width * .31f * pulse, p)
        p.color = Color.rgb(105, 215, 255); c.drawCircle(cx, cy, width * .23f * pulse, p)
        p.color = Color.WHITE; c.drawCircle(cx, cy, width * (.065f + .025f * e), p)
        postInvalidateOnAnimation()
    }
}

/** Waveform reacts to microphone RMS and generated TTS PCM chunks. */
private class SiriWaveView(context: Context) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG); private var rms = 0f; private var listening = false; private var speaking = false; private var phase = 0f
    fun setRms(v: Float) { rms = v; invalidate() }; fun setListening(v: Boolean) { listening = v; invalidate() }; fun setSpeaking(v: Boolean) { speaking = v; invalidate() }
    override fun onDraw(c: Canvas) {
        super.onDraw(c); phase += .06f
        val w = width.toFloat(); val h = height.toFloat(); val cy = h / 2f
        p.color = Color.argb(42, 60, 100, 170); c.drawRoundRect(0f, 0f, w, h, 28f, 28f, p)
        val e = if (listening || speaking) (abs(rms) / 13f).coerceIn(.1f, 1.4f) else .14f
        val bars = 51
        for (i in 0 until bars) {
            val x = (i + .5f) * w / bars; val d = abs(i - bars / 2f) / (bars / 2f); val env = (1f - d * .84f).coerceAtLeast(.06f)
            val pulse = (.25f + .75f * abs(sin(phase * 2.0 + i * .48))).toFloat(); val amp = h * .34f * env * (.28f + e * .9f) * pulse
            p.color = when { i < bars * .2f || i > bars * .8f -> Color.rgb(42, 94, 220); i < bars * .4f || i > bars * .6f -> Color.rgb(82, 185, 255); else -> Color.WHITE }
            c.drawRoundRect(x - 1.7f, cy - amp, x + 1.7f, cy + amp, 4f, 4f, p)
        }
        postInvalidateOnAnimation()
    }
}
'''
p.write_text(source, encoding='utf-8')
assert 'SiriOrbView' in source
assert 'onAudioAvailable' in source
assert 'AstraAgentRuntime(sessionContext)' in source
