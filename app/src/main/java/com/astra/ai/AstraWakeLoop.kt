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

/** Background wake-command compatibility loop. Native KWS can replace it later. */
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
                val phrase = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (phrase.lowercase(Locale.ROOT).contains("astra")) {
                    val command = phrase.substringAfter("astra", "").trim()
                    if (command.isNotBlank()) {
                        scope.launch {
                            val localOnly = context.getSharedPreferences("astra_runtime", Context.MODE_PRIVATE).getBoolean("local_only", true)
                            AstraAgentRuntime(context.applicationContext).handle(command, localOnly)
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
