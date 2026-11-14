package com.example.mangatranslator.ocr

import com.example.mangatranslator.translation.MangaTextNormalizer
import org.junit.Assert.assertEquals
import org.junit.Test

class MangaOcrAndTranslationOptimizationTest {

    @Test
    fun testJapaneseTextNormalization() {
        // Vertical Chōonpu (prolonged sound mark) misread as vertical bar '丨'
        val rawOcr = "コ丨ラを飲みたい"
        val normalized = MangaTextNormalizer.normalize(rawOcr, OcrLanguage.JAPANESE)
        assertEquals("コーラを飲みたい", normalized)

        // Multiple spaces and full-width spaces stripped
        val spacedOcr = "お　前 は　誰 だ"
        val normalizedSpaced = MangaTextNormalizer.normalize(spacedOcr, OcrLanguage.JAPANESE)
        assertEquals("お前は誰だ", normalizedSpaced)

        // Half-width Katakana screams normalized to standard full-width
        val halfWidthOcr = "ｱｱｱｯ!"
        val normalizedHalf = MangaTextNormalizer.normalize(halfWidthOcr, OcrLanguage.JAPANESE)
        assertEquals("アアアッ！", normalizedHalf)

        // Standardize ellipses and question marks
        val punctuatedOcr = "待て...何!?本当に..."
        val normalizedPunc = MangaTextNormalizer.normalize(punctuatedOcr, OcrLanguage.JAPANESE)
        assertEquals("待て……何！？本当に……", normalizedPunc)
    }

    @Test
    fun testChineseAndKoreanTextNormalization() {
        // Chinese: whitespace removed, ellipses standardized
        val rawChinese = "你　好 ... 朋 友"
        val normalizedChinese = MangaTextNormalizer.normalize(rawChinese, OcrLanguage.CHINESE)
        assertEquals("你好……朋友", normalizedChinese)

        // Korean: preserves word boundaries and single spaces
        val rawKorean = "안녕  \n  하세요...  대박"
        val normalizedKorean = MangaTextNormalizer.normalize(rawKorean, OcrLanguage.KOREAN)
        assertEquals("안녕 하세요… 대박", normalizedKorean)
    }
}
