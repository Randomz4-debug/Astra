package com.astra.ai

import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.DownloadConditions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Unicode-safe lyrics translation for Astra.
 *
 * Lyrics are kept as normal Kotlin Strings, so scripts such as Devanagari,
 * Arabic, Cyrillic, Han, Hangul, Latin and other Unicode scripts are preserved.
 * ML Kit supplies on-device translation models for 50+ languages after the
 * required model has been downloaded.
 */
class LyricsTranslationEngine {

    suspend fun detectLanguage(text: String): String =
        suspendCancellableCoroutine { continuation ->
            if (text.isBlank()) {
                continuation.resume("und")
                return@suspendCancellableCoroutine
            }

            LanguageIdentification.getClient()
                .identifyLanguage(text)
                .addOnSuccessListener { code ->
                    if (continuation.isActive) continuation.resume(code ?: "und")
                }
                .addOnFailureListener { error ->
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
        }

    /**
     * Translates lyrics while preserving line breaks.
     * sourceLanguage may be "auto" or a BCP-47 code such as "hi", "en", "ja".
     */
    suspend fun translate(
        lyrics: String,
        sourceLanguage: String = "auto",
        targetLanguage: String
    ): String {
        require(lyrics.isNotBlank()) { "Lyrics cannot be empty." }

        val source = if (sourceLanguage.equals("auto", true)) {
            detectLanguage(lyrics)
        } else {
            sourceLanguage.substringBefore('-').lowercase()
        }

        val target = targetLanguage.substringBefore('-').lowercase()
        if (source == "und") throw IllegalArgumentException("Could not detect the lyrics language.")
        if (target.isBlank() || target == "und") throw IllegalArgumentException("Invalid target language.")
        if (source == target) return lyrics

        val sourceMl = TranslateLanguage.fromLanguageTag(source)
            ?: throw IllegalArgumentException("Lyrics source language is not supported: $source")
        val targetMl = TranslateLanguage.fromLanguageTag(target)
            ?: throw IllegalArgumentException("Lyrics target language is not supported: $target")

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(sourceMl)
            .setTargetLanguage(targetMl)
            .build()

        val translator = Translation.getClient(options)
        return try {
            // Model downloads are explicit. The caller should trigger this only
            // when the user asks to translate; once installed, inference is on-device.
            suspendCancellableCoroutine { continuation ->
                translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
                    .addOnSuccessListener {
                        translator.translate(lyrics)
                            .addOnSuccessListener { result ->
                                if (continuation.isActive) continuation.resume(result)
                            }
                            .addOnFailureListener { error ->
                                if (continuation.isActive) continuation.resumeWithException(error)
                            }
                    }
                    .addOnFailureListener { error ->
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
            }
        } finally {
            translator.close()
        }
    }

    fun isSupportedLanguage(languageTag: String): Boolean =
        TranslateLanguage.fromLanguageTag(languageTag.substringBefore('-').lowercase()) != null
}

/** A lyrics document that deliberately preserves every Unicode character and line break. */
data class LyricsDocument(
    val originalText: String,
    val originalLanguage: String = "auto",
    val translatedText: String? = null,
    val translatedLanguage: String? = null
)
