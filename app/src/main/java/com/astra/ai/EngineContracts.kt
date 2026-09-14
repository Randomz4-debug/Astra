package com.astra.ai

interface AiEngine { suspend fun respond(input: String): String }
interface SpeechEngine { suspend fun transcribe(): String }
interface TtsEngine { fun speak(text: String, languageTag: String? = null); fun stop() }
interface WakeWordEngine { fun start(); fun stop() }
interface VisionEngine { suspend fun describe(): String }

class LocalAiEngine : AiEngine {
    override suspend fun respond(input: String): String = when {
        input.equals("stop", true) -> "Stopping."
        input.contains("hello", true) -> "Hello. I'm Astra."
        else -> "I understood: $input"
    }
}
class LocalSpeechEngine : SpeechEngine { override suspend fun transcribe(): String = "" }

class LocalTtsEngine(private val tts: android.speech.tts.TextToSpeech) : TtsEngine {
    override fun speak(text: String, languageTag: String?) {
        if (!languageTag.isNullOrBlank()) {
            val locale = java.util.Locale.forLanguageTag(languageTag)
            if (locale.language.isNotBlank()) {
                val result = tts.setLanguage(locale)
                if (result == android.speech.tts.TextToSpeech.LANG_MISSING_DATA || result == android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts.language = java.util.Locale.getDefault()
                }
            }
        }
        tts.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "astra")
    }
    override fun stop() = tts.stop()
}
class LocalWakeWordEngine : WakeWordEngine { override fun start() {}; override fun stop() {} }
class LocalVisionEngine : VisionEngine { override suspend fun describe(): String = "Local vision engine is ready." }
