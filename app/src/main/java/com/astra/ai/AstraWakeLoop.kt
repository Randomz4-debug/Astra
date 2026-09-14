package com.astra.ai

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Background wake loop. It uses Android's installed recognition service as a compatibility
 * fallback and only executes commands containing the configured wake phrase. A native
 * Sherpa-ONNX keyword spotter can replace this component without changing the agent API.
 */
class AstraWakeLoop(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var recognizer: SpeechRecognizer? = null
    @Volatile private var running = false

    fun start() {
        if (running || !SpeechRecognizer.isRecognitionAvailable(context)) return
        running = true
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).also { attach(it) }
        listenAgain()
    }

    fun stop() {
        running = false
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        scope.coroutineContext.cancel()
    }

    private fun attach(sr: SpeechRecognizer) {
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() { if (running) listenAgain() }
            override fun onError(error: Int) { if (running) listenAgain() }
            override fun onResults(results: Bundle?) {
                val values = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                val phrase = values.firstOrNull().orEmpty()
                val wake = "astra"
                if (phrase.lowercase(Locale.ROOT).contains(wake)) {
                    val command = phrase.substringAfter(wake, "").trim()
                    if (command.isNotBlank()) {
                        scope.launch {
                            AstraAgentRuntime(context.applicationContext).handle(
                                command,
                                context.getSharedPreferences("astra_runtime", Context.MODE_PRIVATE).getBoolean("local_only", true)
                            )
                        }
                    }
                }
                if (running) listenAgain()
            }
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
    }

    private fun listenAgain() {
        val sr = recognizer ?: return
        if (!running) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, true)
            }
        }
        runCatching { sr.startListening(intent) }
    }
}
