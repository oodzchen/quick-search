package com.tk.quicksearch.search.utils

import java.text.Normalizer
import java.util.LinkedHashMap
import net.sourceforge.pinyin4j.PinyinHelper
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType

private val DIACRITIC_MARKS_REGEX = "\\p{M}+".toRegex()
private val NORMALIZE_WHITESPACE_REGEX = "[\\s\\u00A0]+".toRegex()
private val SEARCH_WHITESPACE_REGEX = "[\\s\\u00A0]+".toRegex()
private val SEARCH_SEPARATOR_REGEX = "[^\\p{L}\\p{Nd}]+".toRegex()
private const val TURKISH_DOTLESS_I = '\u0131'
private const val ASCII_I = 'i'

object SearchTextNormalizer {
    private const val PINYIN_CACHE_SIZE = 2_048
    private val pinyinVariantsCache =
        object : LinkedHashMap<String, List<String>>(PINYIN_CACHE_SIZE, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, List<String>>,
            ): Boolean = size > PINYIN_CACHE_SIZE
        }
    private val pinyinOutputFormat =
        HanyuPinyinOutputFormat().apply {
            caseType = HanyuPinyinCaseType.LOWERCASE
            toneType = HanyuPinyinToneType.WITHOUT_TONE
        }

    /**
     * Normalizes text for search by removing diacritics, folding case, and unifying
     * Turkish dotless i with ascii i so both forms remain searchable.
     * Example: "háll" -> "hall"
     */
    fun normalizeForSearch(text: String): String =
        Normalizer
            .normalize(text, Normalizer.Form.NFD)
            .replace(DIACRITIC_MARKS_REGEX, "")
            .lowercase()
            .replace(TURKISH_DOTLESS_I, ASCII_I)

    /**
     * Normalizes query whitespace so trailing, repeated, and non-breaking spaces
     * are treated consistently across search providers and ranking.
     */
    fun normalizeQueryWhitespace(text: String): String =
        text
            .replace('\u00A0', ' ')
            .replace(NORMALIZE_WHITESPACE_REGEX, " ")
            .trim()

    fun removeSearchWhitespace(text: String): String = text.replace(SEARCH_WHITESPACE_REGEX, "")

    fun compactForSearch(text: String): String = text.replace(SEARCH_SEPARATOR_REGEX, "")

    /**
     * Returns normalized searchable forms for [text]. Chinese text additionally exposes
     * space-separated pinyin, compact pinyin, and pinyin initials. Keeping the original form
     * first preserves the existing ranking semantics for non-Chinese searches.
     */
    fun searchableVariants(text: String): List<String> {
        val normalized = normalizeForSearch(text)
        if (normalized.none(::isChineseCharacter)) return listOf(normalized)

        synchronized(pinyinVariantsCache) {
            pinyinVariantsCache[normalized]?.let { return it }
        }

        val syllables = ArrayList<String>()
        val currentNonChinese = StringBuilder()
        normalized.forEach { character ->
            val pinyin = character.toPinyinOrNull()
            if (pinyin != null) {
                if (currentNonChinese.isNotEmpty()) {
                    syllables += currentNonChinese.toString()
                    currentNonChinese.setLength(0)
                }
                syllables += pinyin
            } else if (character.isLetterOrDigit()) {
                currentNonChinese.append(character)
            } else if (currentNonChinese.isNotEmpty()) {
                syllables += currentNonChinese.toString()
                currentNonChinese.setLength(0)
            }
        }
        if (currentNonChinese.isNotEmpty()) syllables += currentNonChinese.toString()

        val spacedPinyin = syllables.joinToString(" ")
        val compactPinyin = syllables.joinToString("")
        val initials = syllables.joinToString("") { it.firstOrNull()?.toString().orEmpty() }
        val variants =
            listOf(normalized, spacedPinyin, compactPinyin, initials)
                .filter(String::isNotBlank)
                .distinct()

        synchronized(pinyinVariantsCache) {
            pinyinVariantsCache[normalized] = variants
        }
        return variants
    }

    private fun isChineseCharacter(character: Char): Boolean =
        PinyinHelper.toHanyuPinyinStringArray(character) != null

    private fun Char.toPinyinOrNull(): String? =
        PinyinHelper.toHanyuPinyinStringArray(this, pinyinOutputFormat)?.firstOrNull()
}
