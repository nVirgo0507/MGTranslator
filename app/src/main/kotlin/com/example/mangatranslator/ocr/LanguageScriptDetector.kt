package com.example.mangatranslator.ocr

/**
 * Utility to identify whether recognized text contains characters of a specific language script.
 *
 * Used to filter out non-target text (such as English reader UI buttons, chapter headers,
 * page numbers, and foreign text) when scanning manga for translation.
 */
object LanguageScriptDetector {

    /**
     * Checks if the text contains Japanese script:
     * - Hiragana (U+3040..U+309F)
     * - Katakana (U+30A0..U+30FF)
     * - Katakana Phonetic Extensions (U+31F0..U+31FF)
     * - CJK Unified Ideographs / Kanji (U+4E00..U+9FFF, U+3400..U+4DBF)
     */
    fun containsJapaneseScript(text: String): Boolean {
        return text.any { char ->
            val code = char.code
            (code in 0x3040..0x309F) ||
            (code in 0x30A0..0x30FF) ||
            (code in 0x31F0..0x31FF) ||
            (code in 0x4E00..0x9FFF) ||
            (code in 0x3400..0x4DBF)
        }
    }

    /**
     * Checks if the text contains Chinese characters (CJK Ideographs)
     * and does NOT contain Japanese Kana or Korean Hangul.
     */
    fun containsChineseScript(text: String): Boolean {
        var hasCjk = false
        for (char in text) {
            val code = char.code
            // If it contains Kana, it's Japanese, not Chinese
            if ((code in 0x3040..0x309F) || (code in 0x30A0..0x30FF) || (code in 0x31F0..0x31FF)) {
                return false
            }
            // If it contains Hangul, it's Korean, not Chinese
            if ((code in 0xAC00..0xD7AF) || (code in 0x1100..0x11FF) || (code in 0x3130..0x318F)) {
                return false
            }
            if ((code in 0x4E00..0x9FFF) || (code in 0x3400..0x4DBF)) {
                hasCjk = true
            }
        }
        return hasCjk
    }

    /**
     * Checks if the text contains Korean Hangul characters:
     * - Hangul Syllables (U+AC00..U+D7AF)
     * - Hangul Jamo (U+1100..U+11FF)
     * - Hangul Compatibility Jamo (U+3130..U+318F)
     */
    fun containsKoreanScript(text: String): Boolean {
        return text.any { char ->
            val code = char.code
            (code in 0xAC00..0xD7AF) ||
            (code in 0x1100..0x11FF) ||
            (code in 0x3130..0x318F)
        }
    }

    /**
     * Determines whether the given text contains the character script of the chosen [OcrLanguage].
     *
     * Returns false if the text consists exclusively of Latin letters, numbers,
     * punctuation, or scripts belonging to unrelated languages.
     */
    fun matchesLanguage(text: String, language: OcrLanguage): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false

        // Discard isolated single punctuation or symbol noise
        if (trimmed.length == 1 && (trimmed[0].isWhitespace() || trimmed[0] in ".,!?:;-_/\\|*~#@$%^&()[]{}<>\"'")) {
            return false
        }

        return when (language) {
            OcrLanguage.JAPANESE -> containsJapaneseScript(trimmed)
            OcrLanguage.CHINESE -> containsChineseScript(trimmed)
            OcrLanguage.KOREAN -> containsKoreanScript(trimmed)
        }
    }
}
