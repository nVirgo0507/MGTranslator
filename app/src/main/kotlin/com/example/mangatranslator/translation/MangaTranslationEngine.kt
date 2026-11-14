package com.example.mangatranslator.translation

import com.example.mangatranslator.ocr.OcrLanguage
import com.example.mangatranslator.ocr.RecognizedTextBlock
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

enum class TargetLanguage(val code: String, val displayName: String) {
    ENGLISH(TranslateLanguage.ENGLISH, "English"),
    SPANISH(TranslateLanguage.SPANISH, "Spanish"),
    FRENCH(TranslateLanguage.FRENCH, "French"),
    GERMAN(TranslateLanguage.GERMAN, "German"),
    INDONESIAN(TranslateLanguage.INDONESIAN, "Indonesian"),
    VIETNAMESE(TranslateLanguage.VIETNAMESE, "Vietnamese")
}

/**
 * On-device translation engine specialized for Manga dialogue.
 * Performs intelligent text cleaning, automatic model downloading, and in-memory caching.
 */
class MangaTranslationEngine(
    private var sourceLanguage: OcrLanguage = OcrLanguage.JAPANESE,
    private var targetLanguage: TargetLanguage = TargetLanguage.ENGLISH
) {
    private var translator: Translator? = null
    private var isModelDownloaded = false
    private val translationCache = ConcurrentHashMap<String, String>()

    init {
        initTranslator()
    }

    private fun initTranslator() {
        translator?.close()
        val mlkitSource = mapSourceLanguage(sourceLanguage)
        val mlkitTarget = targetLanguage.code

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(mlkitSource)
            .setTargetLanguage(mlkitTarget)
            .build()

        translator = Translation.getClient(options)
        isModelDownloaded = false
    }

    fun setLanguages(source: OcrLanguage, target: TargetLanguage = targetLanguage) {
        if (sourceLanguage != source || targetLanguage != target) {
            sourceLanguage = source
            targetLanguage = target
            translationCache.clear()
            initTranslator()
        }
    }

    /**
     * Ensures the translation model is downloaded onto the device.
     * Can be invoked proactively so translation is instantaneous when reading.
     */
    suspend fun ensureModelDownloaded(
        onProgress: ((String) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val client = translator ?: return@withContext false
        try {
            onProgress?.invoke("Checking on-device translation model...")
            val conditions = DownloadConditions.Builder().build()
            client.downloadModelIfNeeded(conditions).await()
            isModelDownloaded = true
            onProgress?.invoke("Translation model ready!")
            true
        } catch (e: Exception) {
            onProgress?.invoke("Model download pending: ${e.message}")
            false
        }
    }

    /**
     * Translates a list of recognized manga text blocks into English.
     */
    suspend fun translateBlocks(
        blocks: List<RecognizedTextBlock>,
        onStatus: ((String) -> Unit)? = null
    ): List<TranslatedTextBlock> = withContext(Dispatchers.Default) {
        if (blocks.isEmpty()) return@withContext emptyList()

        val client = translator ?: return@withContext blocks.map {
            TranslatedTextBlock(
                originalBlock = it,
                originalText = it.text,
                translatedText = it.text,
                boundingBox = it.boundingBox,
                isVertical = it.isVertical
            )
        }

        // Ensure model is ready before translating
        if (!isModelDownloaded) {
            ensureModelDownloaded(onStatus)
        }

        blocks.map { block ->
            val cleanedOriginal = cleanMangaText(block.text, sourceLanguage, block.isVertical)
            if (cleanedOriginal.isBlank()) {
                return@map TranslatedTextBlock(
                    originalBlock = block,
                    originalText = block.text,
                    translatedText = "",
                    boundingBox = block.boundingBox,
                    isVertical = block.isVertical
                )
            }

            // Check cache first for instant response
            val cached = translationCache[cleanedOriginal]
            val translated = if (cached != null) {
                cached
            } else {
                try {
                    val result = client.translate(cleanedOriginal).await()
                    translationCache[cleanedOriginal] = result
                    result
                } catch (e: Exception) {
                    // Fallback to original text if translation failed
                    cleanedOriginal
                }
            }

            TranslatedTextBlock(
                originalBlock = block,
                originalText = cleanedOriginal,
                translatedText = translated,
                boundingBox = block.boundingBox,
                isVertical = block.isVertical
            )
        }
    }

    /**
     * Preprocesses manga text to optimize translation quality.
     * - Japanese / Chinese: Removes line breaks between kanji/kana without adding spaces.
     * - Korean: Normalizes line breaks to single spaces.
     * - Fixes common manga OCR artifacts (broken ellipses, isolated punctuation).
     */
    private fun cleanMangaText(text: String, language: OcrLanguage, isVertical: Boolean): String {
        var cleaned = text.trim()

        when (language) {
            OcrLanguage.JAPANESE, OcrLanguage.CHINESE -> {
                // Vertical manga columns should be joined continuously without extra spacing
                cleaned = cleaned.replace("\n", "")
                cleaned = cleaned.replace(" ", "")
                // Standardize full-width ellipsis and long dash
                cleaned = cleaned.replace("...", "…")
                cleaned = cleaned.replace("···", "…")
            }
            OcrLanguage.KOREAN -> {
                cleaned = cleaned.replace("\n", " ")
                cleaned = cleaned.replace(Regex("\\s+"), " ")
            }
        }

        return cleaned
    }

    private fun mapSourceLanguage(language: OcrLanguage): String {
        return when (language) {
            OcrLanguage.JAPANESE -> TranslateLanguage.JAPANESE
            OcrLanguage.CHINESE -> TranslateLanguage.CHINESE
            OcrLanguage.KOREAN -> TranslateLanguage.KOREAN
        }
    }

    fun close() {
        translator?.close()
        translator = null
        translationCache.clear()
    }
}
