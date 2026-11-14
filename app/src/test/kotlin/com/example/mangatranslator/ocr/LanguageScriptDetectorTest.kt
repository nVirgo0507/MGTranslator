package com.example.mangatranslator.ocr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageScriptDetectorTest {

    @Test
    fun testJapaneseScriptDetection() {
        // Japanese Hiragana
        assertTrue(LanguageScriptDetector.matchesLanguage("こんにちは", OcrLanguage.JAPANESE))
        // Japanese Katakana
        assertTrue(LanguageScriptDetector.matchesLanguage("ワンピース", OcrLanguage.JAPANESE))
        // Japanese Kanji
        assertTrue(LanguageScriptDetector.matchesLanguage("進撃の巨人", OcrLanguage.JAPANESE))
        // Vertical manga dialogue with punctuation
        assertTrue(LanguageScriptDetector.matchesLanguage("……待て、誰だ！？", OcrLanguage.JAPANESE))
        // Manga sound effect
        assertTrue(LanguageScriptDetector.matchesLanguage("ドカーン", OcrLanguage.JAPANESE))
        // Mixed Japanese + English
        assertTrue(LanguageScriptDetector.matchesLanguage("100%本気だ！", OcrLanguage.JAPANESE))

        // Non-Japanese (English UI, numbers, symbols) must be false
        assertFalse(LanguageScriptDetector.matchesLanguage("MangaTranslator", OcrLanguage.JAPANESE))
        assertFalse(LanguageScriptDetector.matchesLanguage("Start Manga Translator", OcrLanguage.JAPANESE))
        assertFalse(LanguageScriptDetector.matchesLanguage("Page 12/45", OcrLanguage.JAPANESE))
        assertFalse(LanguageScriptDetector.matchesLanguage("Settings & Options", OcrLanguage.JAPANESE))
        assertFalse(LanguageScriptDetector.matchesLanguage("12345", OcrLanguage.JAPANESE))
        assertFalse(LanguageScriptDetector.matchesLanguage("...", OcrLanguage.JAPANESE))
        assertFalse(LanguageScriptDetector.matchesLanguage("", OcrLanguage.JAPANESE))
    }

    @Test
    fun testChineseScriptDetection() {
        // Chinese Hanzi
        assertTrue(LanguageScriptDetector.matchesLanguage("你好世界", OcrLanguage.CHINESE))
        assertTrue(LanguageScriptDetector.matchesLanguage("漫画翻译器", OcrLanguage.CHINESE))

        // Japanese with Kana should not match Chinese
        assertFalse(LanguageScriptDetector.matchesLanguage("こんにちは", OcrLanguage.CHINESE))
        assertFalse(LanguageScriptDetector.matchesLanguage("進撃の巨人", OcrLanguage.CHINESE))

        // English text should not match Chinese
        assertFalse(LanguageScriptDetector.matchesLanguage("MangaTranslator", OcrLanguage.CHINESE))
        assertFalse(LanguageScriptDetector.matchesLanguage("12345", OcrLanguage.CHINESE))
    }

    @Test
    fun testKoreanScriptDetection() {
        // Korean Hangul
        assertTrue(LanguageScriptDetector.matchesLanguage("안녕하세요", OcrLanguage.KOREAN))
        assertTrue(LanguageScriptDetector.matchesLanguage("대박 사건!", OcrLanguage.KOREAN))

        // Japanese and Chinese should not match Korean
        assertFalse(LanguageScriptDetector.matchesLanguage("こんにちは", OcrLanguage.KOREAN))
        assertFalse(LanguageScriptDetector.matchesLanguage("你好世界", OcrLanguage.KOREAN))

        // English text should not match Korean
        assertFalse(LanguageScriptDetector.matchesLanguage("MangaTranslator", OcrLanguage.KOREAN))
    }
}
