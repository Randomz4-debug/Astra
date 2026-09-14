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
import android.speech.tts.Voice
import java.util.Locale

/** Central voice controller for Astra with reliable SpeechRecognizer + TTS lifecycle handling. */
class MultilingualVoiceController(private val context: Context) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = TextToSpeech(context, this)
    private var ttsReady = false
    private var pendingSpeech: Pair<String, String>? = null
    private var recognizer: SpeechRecognizer? = null
    private var languageTag = "auto"
    private var continuous = false
    private var continuousCallback: ((String) -> Unit)? = null
    private val handler = Handler(Looper.getMainLooper())

    init {
        if (SpeechRecognizer.isRecognitionAvailable(context)) recognizer = SpeechRecognizer.createSpeechRecognizer(context)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            ttsReady = true
            tts?.language = Locale.getDefault()
            pendingSpeech?.let { (text, language) -> pendingSpeech = null; speakNow(text, language) }
        } else ttsReady = false
    }

    fun listen(language: String, onResult: (String) -> Unit, onState: (Boolean) -> Unit) {
        continuous = false; continuousCallback = null; startRecognition(language, onResult, onState)
    }

    fun startAlwaysListening(language: String = "auto", onResult: (String) -> Unit) {
        continuous = true; continuousCallback = onResult; startRecognition(language, onResult) { VoiceTelemetry.setListening(it) }
    }

    private fun startRecognition(language: String, onResult: (String) -> Unit, onState: (Boolean) -> Unit) {
        val sr = recognizer ?: run { onState(false); return }
        languageTag = language.trim().ifBlank { "auto" }
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) { onState(true); VoiceTelemetry.setListening(true) }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) { VoiceTelemetry.setRms(v) }
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() { onState(false); VoiceTelemetry.setListening(false) }
            override fun onError(e: Int) { onState(false); VoiceTelemetry.setListening(false); if (continuous) restart() }
            override fun onResults(b: Bundle?) {
                onState(false); VoiceTelemetry.setListening(false)
                b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }?.let(onResult)
                if (continuous) restart()
            }
            override fun onPartialResults(b: Bundle?) {}
            override fun onEvent(t: Int, p: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (languageTag == "auto") Locale.getDefault().toLanguageTag() else languageTag)
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, true)
            }
        }
        runCatching { sr.startListening(intent) }.onFailure { onState(false); if (continuous) restart() }
    }

    private fun restart() { if (continuous) handler.postDelayed({ if (continuous) startRecognition(languageTag, continuousCallback ?: {}, { VoiceTelemetry.setListening(it) }) }, 350) }

    fun stop() {
        continuous = false; continuousCallback = null; handler.removeCallbacksAndMessages(null)
        runCatching { recognizer?.cancel() }; VoiceTelemetry.setListening(false); VoiceTelemetry.setRms(0f)
    }

    fun speak(text: String, language: String = "auto") {
        val clean = text.trim(); if (clean.isBlank()) return
        if (!ttsReady) { pendingSpeech = clean to language; return }
        speakNow(clean, language)
    }

    private fun speakNow(text: String, language: String) {
        val engine = tts ?: return
        val tag = language.trim()
        if (tag.isNotBlank() && tag != "auto") {
            val result = engine.setLanguage(Locale.forLanguageTag(tag))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) engine.language = Locale.getDefault()
        } else engine.language = Locale.getDefault()
        runCatching { engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "astra") }
    }

    fun setVoice(voiceName: String): Boolean = runCatching {
        val selected = tts?.voices?.firstOrNull { it.name == voiceName } ?: return false
        tts?.voice = selected; true
    }.getOrDefault(false)

    fun availableVoices(): List<Voice> = tts?.voices?.toList().orEmpty()

    fun release() {
        stop(); recognizer?.destroy(); recognizer = null; tts?.stop(); tts?.shutdown(); tts = null; ttsReady = false; pendingSpeech = null
    }
}
