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
import android.view.WindowManager
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

/** Compact Siri-like VoiceInteractionSession. It does not launch AstraMainActivity. */
class AstraVoiceInteractionSession(private val sessionContext: Context) : VoiceInteractionSession(sessionContext) {
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: String? = null
    private var destroyed = false
    private var expanded = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var root: FrameLayout
    private lateinit var orb: SiriOrbView
    private lateinit var panel: LinearLayout
    private lateinit var status: TextView
    private lateinit var transcript: TextView
    private lateinit var answer: TextView
    private lateinit var input: EditText

    private fun assistantName(): String = sessionContext.applicationContext.getSharedPreferences("astra_runtime", Context.MODE_PRIVATE).getString("assistant_name", "Astra")?.trim()?.ifBlank { "Astra" } ?: "Astra"

    override fun onCreate() {
        super.onCreate()
        runCatching { window?.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON) }
    }

    override fun onCreateContentView(): View {
        val name = assistantName()
        root = FrameLayout(sessionContext).apply { setBackgroundColor(Color.TRANSPARENT); setPadding(12, 0, 12, 18) }
        orb = SiriOrbView(sessionContext).apply {
            contentDescription = "$name assistant"
            setOnClickListener { expanded = !expanded; panel.visibility = if (expanded) View.VISIBLE else View.GONE; visibility = if (expanded) View.GONE else View.VISIBLE }
            setOnLongClickListener { stopEverything(); true }
        }
        root.addView(orb, FrameLayout.LayoutParams(94, 94, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        panel = LinearLayout(sessionContext).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(18, 14, 18, 14); background = rounded(Color.argb(242, 7, 9, 15), 30f); visibility = View.GONE }
        val title = TextView(sessionContext).apply { text = name; textSize = 19f; setTextColor(Color.WHITE); gravity = Gravity.CENTER }
        status = TextView(sessionContext).apply { text = "Listening…"; textSize = 12f; setTextColor(Color.LTGRAY); gravity = Gravity.CENTER }
        val wave = SiriWaveView(sessionContext); orb.bindWave(wave)
        transcript = TextView(sessionContext).apply { textSize = 15f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; maxLines = 3 }
        answer = TextView(sessionContext).apply { textSize = 15f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; maxLines = 5; visibility = View.GONE }
        input = EditText(sessionContext).apply { hint = "Ask $name…"; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); maxLines = 3; background = rounded(Color.rgb(25, 28, 36), 22f); setPadding(14, 8, 14, 8) }
        val controls = LinearLayout(sessionContext).apply { gravity = Gravity.CENTER }
        val mic = ImageButton(sessionContext).apply { setImageResource(android.R.drawable.ic_btn_speak_now); setColorFilter(Color.WHITE); background = rounded(Color.rgb(35, 38, 48), 100f); contentDescription = "Talk to $name"; setOnClickListener { if (recognizer != null) stopListening() else startListening() } }
        val send = ImageButton(sessionContext).apply { setImageResource(android.R.drawable.ic_menu_send); setColorFilter(Color.WHITE); background = rounded(Color.rgb(35, 70, 110), 100f); contentDescription = "Send"; setOnClickListener { submit(input.text.toString()) } }
        controls.addView(mic, LinearLayout.LayoutParams(54, 54).apply { marginEnd = 10 }); controls.addView(send, LinearLayout.LayoutParams(54, 54))
        panel.addView(title, LinearLayout.LayoutParams(-1, 28)); panel.addView(status, LinearLayout.LayoutParams(-1, 22)); panel.addView(wave, LinearLayout.LayoutParams(-1, 68)); panel.addView(transcript, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT)); panel.addView(answer, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT)); panel.addView(input, LinearLayout.LayoutParams(-1, 50).apply { topMargin = 7 }); panel.addView(controls, LinearLayout.LayoutParams(-1, 58).apply { topMargin = 7 })
        root.addView(panel, FrameLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        tts = TextToSpeech(sessionContext) { result ->
            ttsReady = result == TextToSpeech.SUCCESS
            if (ttsReady) {
                val r = tts?.setLanguage(Locale.getDefault()) ?: TextToSpeech.ERROR
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) tts?.language = Locale.US
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) { orb.setSpeaking(true); VoiceTelemetry.setSpeaking(true) }
                    override fun onAudioAvailable(id: String?, audio: ByteArray?) {
                        if (audio == null || audio.size < 2) return
                        val b = ByteBuffer.wrap(audio).order(ByteOrder.LITTLE_ENDIAN); var sum = 0.0; var count = 0
                        while (b.remaining() >= 2) { val s = b.short.toDouble(); sum += s * s; count++ }
                        if (count > 0) { val rms = sqrt(sum / count).toFloat(); val db = 20f * log10((rms / 32768f).coerceAtLeast(.00001f)); orb.setRms(db); VoiceTelemetry.setRms(db) }
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
        if (destroyed || !SpeechRecognizer.isRecognitionAvailable(sessionContext)) { status.text = "Speech recognition unavailable"; return }
        recognizer?.destroy(); VoiceTelemetry.setListening(true); orb.setListening(true); status.text = "Listening…"; transcript.text = ""; answer.visibility = View.GONE
        recognizer = SpeechRecognizer.createSpeechRecognizer(sessionContext).also { r ->
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) { status.text = "Listening…" }
                override fun onBeginningOfSpeech() { status.text = "Listening…" }
                override fun onRmsChanged(v: Float) { VoiceTelemetry.setRms(v); orb.setRms(v) }
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() { VoiceTelemetry.setListening(false); orb.setListening(false); status.text = "Thinking…" }
                override fun onError(e: Int) { VoiceTelemetry.setListening(false); orb.setListening(false); recognizer = null; status.text = when (e) { SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required"; SpeechRecognizer.ERROR_NO_MATCH -> "I didn't catch that"; SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer is busy"; else -> "Speech error ($e)" } }
                override fun onResults(results: Bundle?) { VoiceTelemetry.setListening(false); orb.setListening(false); val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty(); if (text.isBlank()) status.text = "I didn't catch that" else { transcript.text = text; submit(text) } }
                override fun onPartialResults(results: Bundle?) { results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { transcript.text = it } }
                override fun onEvent(type: Int, params: Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply { putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM); putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true); putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3); putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag()) }
            runCatching { r.startListening(intent) }.onFailure { VoiceTelemetry.setListening(false); orb.setListening(false); status.text = "Could not start listening" }
        }
    }
    private fun stopListening() { runCatching { recognizer?.cancel() }; recognizer?.destroy(); recognizer = null; VoiceTelemetry.setListening(false); orb.setListening(false); status.text = "Ready" }
    private fun submit(text: String) { val clean = text.trim(); if (clean.isBlank() || destroyed) return; transcript.text = clean; status.text = "Thinking…"; answer.visibility = View.GONE; scope.launch(Dispatchers.IO) { val result = runCatching { AstraAgentRuntime(sessionContext).handle(clean, false) }.getOrElse { "Assistant error: ${it.message ?: "unknown error"}" }; launch(Dispatchers.Main) { if (!destroyed) { answer.text = result; answer.visibility = View.VISIBLE; input.setText(""); status.text = assistantName(); speak(result) } } } }
    private fun speak(text: String) { if (text.isBlank() || destroyed) return; if (!ttsReady) { pendingSpeech = text; return }; orb.setSpeaking(true); VoiceTelemetry.setSpeaking(true); runCatching { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "astra-${System.currentTimeMillis()}") } }
    private fun stopEverything() { stopListening(); runCatching { tts?.stop() }; orb.setSpeaking(false); VoiceTelemetry.setSpeaking(false); status.text = "Stopped" }
    override fun onShow(args: Bundle?, showFlags: Int) { super.onShow(args, showFlags); root.post { if (!destroyed) startListening() } }
    override fun onHide() { stopEverything(); super.onHide() }
    override fun onDestroy() { destroyed = true; stopEverything(); tts?.shutdown(); tts = null; scope.cancel(); super.onDestroy() }
    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = radius }
}

private class SiriOrbView(context: Context) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG); private var rms = 0f; private var listening = false; private var speaking = false; private var phase = 0f; private var wave: SiriWaveView? = null
    fun bindWave(v: SiriWaveView) { wave = v }; fun setRms(v: Float) { rms = v; wave?.setRms(v); invalidate() }; fun setListening(v: Boolean) { listening = v; wave?.setListening(v); invalidate() }; fun setSpeaking(v: Boolean) { speaking = v; wave?.setSpeaking(v); invalidate() }
    override fun onDraw(c: Canvas) { phase += .08f; val cx = width / 2f; val cy = height / 2f; val e = if (listening || speaking) (abs(rms) / 14f).coerceIn(.12f, 1.4f) else .16f; val pulse = 1f + .07f * sin(phase * 2f).toFloat() + .12f * e; p.style = Paint.Style.FILL; p.color = Color.argb(55, 70, 170, 255); c.drawCircle(cx, cy, 42f * pulse, p); p.color = Color.rgb(35, 75, 185); c.drawCircle(cx, cy, 32f * pulse, p); p.color = Color.rgb(90, 205, 255); c.drawCircle(cx, cy, 25f * pulse, p); p.color = Color.WHITE; c.drawCircle(cx, cy, 8f + 5f * e, p); postInvalidateOnAnimation() }
}

/** Waveform reacts to microphone RMS and generated TTS PCM chunks. */
private class SiriWaveView(context: Context) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG); private var rms = 0f; private var listening = false; private var speaking = false; private var phase = 0f
    fun setRms(v: Float) { rms = v; invalidate() }; fun setListening(v: Boolean) { listening = v; invalidate() }; fun setSpeaking(v: Boolean) { speaking = v; invalidate() }
    override fun onDraw(c: Canvas) { super.onDraw(c); phase += .06f; val w = width.toFloat(); val h = height.toFloat(); val cy = h / 2f; p.color = Color.argb(38, 40, 120, 255); c.drawRoundRect(0f, 0f, w, h, 25f, 25f, p); val e = if (listening || speaking) (abs(rms) / 13f).coerceIn(.1f, 1.4f) else .14f; val bars = 47; for (i in 0 until bars) { val x = (i + .5f) * w / bars; val d = abs(i - bars / 2f) / (bars / 2f); val env = (1f - d * .84f).coerceAtLeast(.06f); val pulse = (.25f + .75f * abs(sin(phase * 2.0 + i * .48))).toFloat(); val amp = h * .34f * env * (.28f + e * .9f) * pulse; p.color = when { i < bars * .2f || i > bars * .8f -> Color.rgb(40, 100, 255); i < bars * .4f || i > bars * .6f -> Color.rgb(80, 190, 255); else -> Color.WHITE }; c.drawRoundRect(x - 1.6f, cy - amp, x + 1.6f, cy + amp, 4f, 4f, p) }; postInvalidateOnAnimation() }
}
'''
p.write_text(source, encoding='utf-8')
assert 'SiriOrbView' in source
assert 'onAudioAvailable' in source
assert 'FLAG_SHOW_WHEN_LOCKED' in source
