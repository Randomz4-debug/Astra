package com.astra.ai

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale

class MultilingualVoiceController(private val context: Context) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = TextToSpeech(context, this)
    private var recognizer: SpeechRecognizer? = if (SpeechRecognizer.isRecognitionAvailable(context)) SpeechRecognizer.createSpeechRecognizer(context) else null
    private var languageTag = "auto"

    override fun onInit(status: Int) { if (status == TextToSpeech.SUCCESS) tts?.language = Locale.getDefault() }

    fun listen(language: String, onResult: (String) -> Unit, onState: (Boolean) -> Unit) {
        val sr = recognizer ?: return
        languageTag = language.trim().ifBlank { "auto" }
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) { onState(true) }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() { onState(false) }
            override fun onError(e: Int) { onState(false) }
            override fun onResults(b: Bundle?) { onState(false); b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onResult) }
            override fun onPartialResults(b: Bundle?) {}
            override fun onEvent(t: Int, p: Bundle?) {}
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (languageTag == "auto") Locale.getDefault().toLanguageTag() else languageTag)
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, true)
            }
        }
        sr.startListening(i)
    }

    fun stop() { recognizer?.stopListening() }

    fun speak(text: String, language: String = "auto") {
        val engine = tts ?: return
        val tag = language.trim()
        if (tag.isNotBlank() && tag != "auto") {
            val result = engine.setLanguage(Locale.forLanguageTag(tag))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) engine.language = Locale.getDefault()
        }
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "astra")
    }

    fun release() { recognizer?.destroy(); recognizer = null; tts?.stop(); tts?.shutdown(); tts = null }
}
