package app.tanuki.core.domain.chapter.service

import app.tanuki.core.domain.chapter.model.ChapterNumber
import app.tanuki.core.domain.chapter.model.chapter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChapterRecognitionTest {
    private fun parse(
        mangaTitle: String,
        chapterName: String
    ): ChapterNumber? = ChapterRecognition.parseChapterNumber(mangaTitle, chapterName)

    // region known chapter number short-circuits parsing

    @Test
    fun `returns the given chapterNumber when one is already known`() {
        val result =
            ChapterRecognition.parseChapterNumber(
                mangaTitle = "Bleach",
                chapterName = "completely unparseable name",
                chapterNumber = 42.chapter
            )
        assertEquals(42.chapter, result)
    }

    @Test
    fun `prefers the known chapterNumber over what the name would parse to`() {
        val result =
            ChapterRecognition.parseChapterNumber(
                mangaTitle = "Bleach",
                chapterName = "Bleach 567",
                chapterNumber = 1.chapter
            )
        assertEquals(1.chapter, result)
    }

    // endregion

    // region "Ch." prefixed names

    @Test
    fun `parses a Ch dot prefixed number`() {
        assertEquals(4.chapter, parse("Mokushiroku Alice", "Mokushiroku Alice Vol.1 Ch. 4: Misrepresentation"))
    }

    @Test
    fun `picks the Ch number over a leading volume number`() {
        assertEquals(5.chapter, parse("Some Manga", "Vol.1 Ch.5"))
    }

    // endregion

    // region plain trailing numbers

    @Test
    fun `parses a plain trailing chapter number`() {
        assertEquals(567.chapter, parse("Bleach", "Bleach 567: Down With Snowwhite"))
    }

    @Test
    fun `strips the manga title before parsing the number`() {
        assertEquals(145.chapter, parse("Tower of God", "Tower of God 145"))
    }

    @Test
    fun `is case insensitive when stripping the manga title`() {
        assertEquals(145.chapter, parse("TOWER OF GOD", "tower of god 145"))
    }

    // endregion

    // region unwanted volume-like tags

    @Test
    fun `ignores volume and version tags when a real chapter number is present`() {
        assertEquals(12.chapter, parse("Prison School", "Prison School 12 v.1 vol004 version1243 volume64"))
    }

    @Test
    fun `ignores a season tag`() {
        assertEquals(8.chapter, parse("Some Manga", "Some Manga S2 8"))
    }

    // endregion

    // region sub-chapter handling (decimals are preserved)

    @Test
    fun `decimal sub-chapter keeps its fractional part`() {
        assertEquals(4.5.chapter, parse("Some Manga", "Ch.4.5"))
    }

    @Test
    fun `extra suffix maps to a 0_99 offset`() {
        // 4 + 0.99 -> 4.99
        assertEquals(4.99.chapter, parse("Some Manga", "Ch.4 extra"))
    }

    @Test
    fun `alpha postfix maps to a decimal sub-chapter`() {
        // 4.a -> 4.1
        assertEquals(4.1.chapter, parse("Some Manga", "Ch.4.a"))
    }

    @Test
    fun `comma is treated as a decimal separator`() {
        // "1,5" -> "1.5"
        assertEquals(1.5.chapter, parse("One Piece", "One Piece 1,5"))
    }

    // endregion

    // region no number

    @Test
    fun `returns null when there is no number to parse`() {
        assertNull(parse("Bleach", "Bleach: A Side Story"))
    }

    @Test
    fun `returns null for an empty chapter name`() {
        assertNull(parse("Bleach", ""))
    }

    @Test
    fun `returns null when the only number is the manga title itself`() {
        // The title (including its number) is stripped, leaving nothing to parse.
        assertNull(parse("7 Seeds", "7 Seeds"))
    }

    // endregion

    // region batch parsing

    @Test
    fun `parses every chapter - result positionally aligned with input`() {
        val result =
            ChapterRecognition.parseChapterNumbers(
                mangaTitle = "Bleach",
                chapters =
                    listOf(
                        "Bleach 567: Down With Snowwhite" to null,
                        "Bleach: A Side Story" to null, // no number → null
                        "Ch.4.5" to null
                    )
            )
        assertEquals(listOf(567.chapter, null, 4.5.chapter), result)
    }

    @Test
    fun `batch passes through an already-known number without parsing the name`() {
        val result =
            ChapterRecognition.parseChapterNumbers(
                mangaTitle = "Bleach",
                chapters = listOf("completely unparseable name" to 42.chapter)
            )
        assertEquals(listOf(42.chapter), result)
    }

    @Test
    fun `batch matches the single-call result for each chapter`() {
        val title = "Tower of God"
        val names = listOf("Tower of God 145", "Vol.1 Ch.5", "Ch.4 extra", "no number here")
        val batch = ChapterRecognition.parseChapterNumbers(title, names.map { it to null })
        val oneByOne = names.map { parse(title, it) }
        assertEquals(oneByOne, batch)
    }

    @Test
    fun `batch of no chapters is an empty list`() {
        assertEquals(emptyList<ChapterNumber?>(), ChapterRecognition.parseChapterNumbers("Bleach", emptyList()))
    }

    // endregion
}