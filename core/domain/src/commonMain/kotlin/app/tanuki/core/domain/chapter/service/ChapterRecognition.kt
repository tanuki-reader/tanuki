package app.tanuki.core.domain.chapter.service

import app.tanuki.core.domain.chapter.model.ChapterNumber

object ChapterRecognition {
    private const val NUMBER_PATTERN = """([0-9]+)(\.[0-9]+)?(\.?[a-z]+)?"""

    /**
     * All cases with Ch.xx
     * Mokushiroku Alice Vol. 1 Ch. 4: Misrepresentation -R> 4
     */
    private val basic = Regex("""(?<=ch\.) *$NUMBER_PATTERN""")

    /**
     * Example: Bleach 567: Down With Snowwhite -R> 567
     */
    private val number = Regex(NUMBER_PATTERN)

    /**
     * Regex used to remove unwanted tags
     * Example: Prison School 12 v.1 vol004 version1243 volume64 -R> Prison School 12
     */
    private val unwanted = Regex("""\b(?:v|ver|vol|version|volume|season|s)[^a-z]?[0-9]+""")

    /**
     * Regex used to remove unwanted whitespace
     * Example: One Piece 12 special -R> One Piece 12special
     */
    private val unwantedWhiteSpace = Regex("""\s(?=extra|special|omake)""")

    /**
     * Returns the chapter number parsed from [chapterName], or [chapterNumber] if already known.
     *
     * Returns `null` when the number cannot be determined. When parsing many chapters of the same
     * manga, prefer [parseChapterNumbers], which lowercases [mangaTitle] once instead of per call.
     */
    fun parseChapterNumber(
        mangaTitle: String,
        chapterName: String,
        chapterNumber: ChapterNumber? = null
    ): ChapterNumber? = parseWithNormalizedTitle(mangaTitle.lowercase(), chapterName, chapterNumber)

    /**
     * Batch variant for parsing every chapter of a single manga. Lowercases [mangaTitle] once and
     * reuses it across all [chapters], avoiding the per-chapter title normalization that the
     * single-call [parseChapterNumber] would repeat in a loop.
     *
     * Each entry pairs a chapter name with its already-known number (or `null`); the result is
     * positionally aligned with [chapters].
     */
    fun parseChapterNumbers(
        mangaTitle: String,
        chapters: List<Pair<String, ChapterNumber?>>
    ): List<ChapterNumber?> {
        val normalizedTitle = mangaTitle.lowercase()
        return chapters.map { (chapterName, chapterNumber) ->
            parseWithNormalizedTitle(normalizedTitle, chapterName, chapterNumber)
        }
    }

    /**
     * Core parsing routine shared by [parseChapterNumber] and [parseChapterNumbers]. Takes the
     * manga title **already lowercased** so callers control how often that normalization happens.
     */
    private fun parseWithNormalizedTitle(
        normalizedTitle: String,
        chapterName: String,
        chapterNumber: ChapterNumber?
    ): ChapterNumber? {
        if (chapterNumber != null) return chapterNumber

        val cleanChapterName =
            chapterName
                .lowercase()
                .replace(normalizedTitle, "")
                .trim()
                .replace(',', '.')
                .replace('-', '.')
                .replace(unwantedWhiteSpace, "")

        val matches = number.findAll(cleanChapterName).take(2).toList()

        return when (matches.size) {
            0 -> {
                null
            }

            1 -> {
                getChapterNumberFromMatch(matches[0])
            }

            else -> {
                val stripped = unwanted.replace(cleanChapterName, "")
                basic.find(stripped)?.let { return getChapterNumberFromMatch(it) }
                number.find(stripped)?.let { return getChapterNumberFromMatch(it) }
                getChapterNumberFromMatch(matches[0])
            }
        }
    }

    private fun getChapterNumberFromMatch(match: MatchResult): ChapterNumber {
        val initial = match.groups[1]?.value?.toDouble()!!
        val subChapterDecimal = match.groups[2]?.value
        val subChapterAlpha = match.groups[3]?.value
        val addition = checkForDecimal(subChapterDecimal, subChapterAlpha)
        return ChapterNumber(initial.plus(addition))
    }

    private fun checkForDecimal(
        decimal: String?,
        alpha: String?
    ): Double {
        if (!decimal.isNullOrEmpty()) return decimal.toDouble()

        if (!alpha.isNullOrEmpty()) {
            if (alpha.contains("extra")) return 0.99
            if (alpha.contains("omake")) return 0.98
            if (alpha.contains("special")) return 0.97

            val trimmedAlpha = alpha.trimStart('.')
            if (trimmedAlpha.length == 1) {
                return parseAlphaPostFix(trimmedAlpha[0])
            }
        }

        return 0.0
    }

    /** x.a → x.1, x.b → x.2, etc. */
    private fun parseAlphaPostFix(alpha: Char): Double {
        val number = alpha.code - ('a'.code - 1)
        if (number >= 10) return 0.0
        return number / 10.0
    }
}