package com.astra.ai

import android.content.Context

interface AiEngine { suspend fun respond(input: String): String }
interface SpeechEngine { suspend fun transcribe(): String }
interface TtsEngine { fun speak(text: String, languageTag: String? = null); fun stop() }
interface WakeWordEngine { fun start(); fun stop() }
interface VisionEngine { suspend fun describe(): String }

/** Local-first engine backed by an explicitly configured local AI runtime. */
class LocalAiEngine(private val context: Context) : AiEngine {
    private val gateway = LocalAiGateway(context)

    override suspend fun respond(input: String): String {
        if (input.equals("stop", true) || input.equals("astra stop", true)) return "Stopping."
        return runCatching { gateway.chat(input) }.getOrElse {
            when {
                input.contains("hello", true) -> "Hello. I'm Astra. Local AI runtime is not connected yet."
                else -> "I understood: $input\n\nNo local model runtime is connected. Configure a loopback/private Ollama-compatible runtime or add a native GGUF backend."
            }
        }
    }
}

class LocalSpeechEngine : SpeechEngine { override suspend fun transcribe(): String = "" }

class LocalTtsEngine(private val tts: android.speech.tts.TextToSpeech) : TtsEngine {
    override fun speak(text: String, languageTag: String?) {
        if (!languageTag.isNullOrBlank() && languageTag != "auto") {
            val locale = java.util.Locale.forLanguageTag(languageTag)
            if (locale.language.isNotBlank()) {
                val result = tts.setLanguage(locale)
                if (result == android.speech.tts.TextToSpeech.LANG_MISSING_DATA ||
                    result == android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED
                ) tts.language = java.util.Locale.getDefault()
            }
        }
        tts.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "astra")
    }
    override fun stop() { tts.stop() }
}

class LocalWakeWordEngine : WakeWordEngine {
    override fun start() {}
    override fun stop() {}
}

class LocalVisionEngine : VisionEngine {
    override suspend fun describe(): String = "Local vision engine is ready."
}
