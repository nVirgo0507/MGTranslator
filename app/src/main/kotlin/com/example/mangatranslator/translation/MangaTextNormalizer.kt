package com.example.mangatranslator.translation

import com.example.mangatranslator.ocr.OcrLanguage

/**
 * Preprocessing and normalization engine for manga dialogue text before feeding into NMT.
 *
 * Solves common OCR misrecognitions and quirks specific to manga typography:
 * - Vertical prolonged sound marks (Chōonpu) misrecognized as vertical bar '丨', '｜', or '―'
 * - Half-width Katakana screams / SFX normalized to standard full-width Katakana
 * - Spurious whitespace and full-width spaces removed for Japanese / Chinese
 * - Ellipses and punctuation standardized
 */
object MangaTextNormalizer {

    /**
     * Normalizes half-width Katakana (U+FF61..U+FF9F) to standard full-width Katakana (U+30A1..U+30F6).
     */
    fun halfWidthToFullWidth(text: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            // Handle voiced marks (e.g. ｶﾞ -> ガ)
            if (i + 1 < text.length && text[i + 1] == 'ﾞ') {
                val combined = mapHalfWidthVoiced(c)
                if (combined != null) {
                    sb.append(combined)
                    i += 2
                    continue
                }
            } else if (i + 1 < text.length && text[i + 1] == 'ﾟ') {
                // Semi-voiced marks (e.g. ﾊﾟ -> パ)
                val combined = mapHalfWidthSemiVoiced(c)
                if (combined != null) {
                    sb.append(combined)
                    i += 2
                    continue
                }
            }

            val full = mapHalfWidthSingle(c)
            sb.append(full ?: c)
            i++
        }
        return sb.toString()
    }

    /**
     * Fixes common OCR errors in Japanese manga:
     * - Vertical Chōonpu (prolonged sound mark 'ー') misread as '丨', '｜', '|', or '―'
     * - Strips spaces (Japanese does not use spaces; OCR spaces break NMT word tokenization)
     * - Standardizes ellipses, wave dashes, and exclamation marks
     */
    fun normalizeJapanese(text: String): String {
        var result = halfWidthToFullWidth(text)

        // Remove all whitespace
        result = result.replace(" ", "")
            .replace("　", "") // Full-width space
            .replace("\t", "")
            .replace("\r", "")
            .replace("\n", "")

        // Fix vertical Chōonpu misread as vertical bar '丨', '｜', '|', or '―'
        // Preceded by Kana: e.g. [Kana][丨｜|―][Kana/Punctuation]
        result = result.replace(Regex("([\\u3040-\\u309F\\u30A0-\\u30FF])[丨｜\\|―]([\\u3040-\\u309F\\u30A0-\\u30FF！？…])?")) { match ->
            val prev = match.groupValues[1]
            val next = match.groupValues.getOrNull(2) ?: ""
            "${prev}ー$next"
        }

        // Standardize ellipses
        result = result.replace("...", "…")
            .replace("···", "…")
            .replace("・・・", "…")
            .replace("‥", "…")
            .replace(Regex("…+"), "……")

        // Standardize wave dash
        result = result.replace("~", "〜").replace("～", "〜")

        // Standardize exclamation and question marks
        result = result.replace("!?", "！？")
            .replace("?!", "！？")
            .replace("!!", "！！")
            .replace("??", "？？")
            .replace("!", "！")
            .replace("?", "？")

        return result.trim()
    }

    /**
     * Normalizes Chinese dialogue: strips whitespace, standardizes punctuation.
     */
    fun normalizeChinese(text: String): String {
        var result = text.replace(" ", "")
            .replace("　", "")
            .replace("\t", "")
            .replace("\r", "")
            .replace("\n", "")
            .replace("...", "…")
            .replace("···", "…")
            .replace(Regex("…+"), "……")
            .replace("!", "！")
            .replace("?", "？")
        return result.trim()
    }

    /**
     * Normalizes Korean dialogue: preserves single word spaces, collapses newlines.
     */
    fun normalizeKorean(text: String): String {
        var result = text.replace("\r", "")
            .replace("\n", " ")
            .replace(Regex("\\s+"), " ")
            .replace("...", "…")
        return result.trim()
    }

    /**
     * Applies language-specific normalization to manga dialogue.
     */
    fun normalize(text: String, language: OcrLanguage): String {
        return when (language) {
            OcrLanguage.JAPANESE -> normalizeJapanese(text)
            OcrLanguage.CHINESE -> normalizeChinese(text)
            OcrLanguage.KOREAN -> normalizeKorean(text)
        }
    }

    private fun mapHalfWidthSingle(c: Char): Char? = when (c) {
        'ｦ' -> 'ヲ'; 'ｧ' -> 'ァ'; 'ｨ' -> 'ィ'; 'ｩ' -> 'ゥ'; 'ｪ' -> 'ェ'; 'ｫ' -> 'ォ'
        'ｬ' -> 'ャ'; 'ｭ' -> 'ュ'; 'ｮ' -> 'ョ'; 'ｯ' -> 'ッ'; 'ｰ' -> 'ー'; 'ｱ' -> 'ア'
        'ｲ' -> 'イ'; 'ｳ' -> 'ウ'; 'ｴ' -> 'エ'; 'ｵ' -> 'オ'; 'ｶ' -> 'カ'; 'ｷ' -> 'キ'
        'ｸ' -> 'ク'; 'ｹ' -> 'ケ'; 'ｺ' -> 'コ'; 'ｻ' -> 'サ'; 'ｼ' -> 'シ'; 'ｽ' -> 'ス'
        'ｾ' -> 'セ'; 'ｿ' -> 'ソ'; 'ﾀ' -> 'タ'; 'ﾁ' -> 'チ'; 'ﾂ' -> 'ツ'; 'ﾃ' -> 'テ'
        'ﾄ' -> 'ト'; 'ﾅ' -> 'ナ'; 'ﾆ' -> 'ニ'; 'ﾇ' -> 'ヌ'; 'ﾈ' -> 'ネ'; 'ﾉ' -> 'ノ'
        'ﾊ' -> 'ハ'; 'ﾋ' -> 'ヒ'; 'ﾌ' -> 'フ'; 'ﾍ' -> 'ヘ'; 'ﾎ' -> 'ホ'; 'ﾏ' -> 'マ'
        'ﾐ' -> 'ミ'; 'ﾑ' -> 'ム'; 'ﾒ' -> 'メ'; 'ﾓ' -> 'モ'; 'ﾔ' -> 'ヤ'; 'ﾕ' -> 'ユ'
        'ﾖ' -> 'ヨ'; 'ﾗ' -> 'ラ'; 'ﾘ' -> 'リ'; 'ﾙ' -> 'ル'; 'ﾚ' -> 'レ'; 'ﾛ' -> 'ロ'
        'ﾜ' -> 'ワ'; 'ﾝ' -> 'ン'; else -> null
    }

    private fun mapHalfWidthVoiced(c: Char): Char? = when (c) {
        'ｳ' -> 'ヴ'; 'ｶ' -> 'ガ'; 'ｷ' -> 'ギ'; 'ｸ' -> 'グ'; 'ｹ' -> 'ゲ'; 'ｺ' -> 'ゴ'
        'ｻ' -> 'ザ'; 'ｼ' -> 'ジ'; 'ｽ' -> 'ズ'; 'ｾ' -> 'ゼ'; 'ｿ' -> 'ゾ'; 'ﾀ' -> 'ダ'
        'ﾁ' -> 'ヂ'; 'ﾂ' -> 'ヅ'; 'ﾃ' -> 'デ'; 'ﾄ' -> 'ド'; 'ﾊ' -> 'バ'; 'ﾋ' -> 'ビ'
        'ﾌ' -> 'ブ'; 'ﾍ' -> 'ベ'; 'ﾎ' -> 'ボ'; else -> null
    }

    private fun mapHalfWidthSemiVoiced(c: Char): Char? = when (c) {
        'ﾊ' -> 'パ'; 'ﾋ' -> 'ピ'; 'ﾌ' -> 'プ'; 'ﾍ' -> 'ペ'; 'ﾎ' -> 'ポ'; else -> null
    }
}
