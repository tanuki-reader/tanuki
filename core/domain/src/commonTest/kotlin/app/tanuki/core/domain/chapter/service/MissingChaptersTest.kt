package app.tanuki.core.domain.chapter.service

import app.tanuki.core.domain.chapter.model.Chapter
import app.tanuki.core.domain.chapter.model.ChapterNumber
import app.tanuki.core.domain.chapter.model.chapter
import app.tanuki.core.domain.common.model.Text
import app.tanuki.core.domain.common.model.Url
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlin.uuid.Uuid

class MissingChaptersTest {
    private fun chapterWith(number: ChapterNumber?): Chapter =
        Chapter(
            id = Uuid.random(),
            mangaId = Uuid.random(),
            chapterNumber = number,
            name = Text("Chapter"),
            scanlator = null,
            sourceChapterKey = Text("c-$number"),
            url = Url("https://example.test/c"),
            dateUpload = null,
            dateFetch = Instant.fromEpochSeconds(0L),
            progress = null
        )

    // region missingChaptersCount

    @Test
    fun `empty list has no missing chapters`() {
        assertEquals(0u, emptyList<ChapterNumber>().missingChaptersCount())
    }

    @Test
    fun `consecutive chapters have no gap`() {
        assertEquals(0u, listOf(1.chapter, 2.chapter, 3.chapter).missingChaptersCount())
    }

    @Test
    fun `a single gap counts one missing chapter`() {
        assertEquals(1u, listOf(1.chapter, 3.chapter).missingChaptersCount())
    }

    @Test
    fun `counts missing chapters across multiple gaps`() {
        // 1->3 misses 2 (1); 3->4 none; 4->10 misses 5..9 (5) => 6 total
        assertEquals(6u, listOf(1.chapter, 3.chapter, 4.chapter, 10.chapter).missingChaptersCount())
    }

    @Test
    fun `sub-chapters and duplicates do not count as missing`() {
        // collapses to whole chapters 1, 1, 2 -> no gap
        assertEquals(0u, listOf(1.chapter, 1.5.chapter, 2.chapter).missingChaptersCount())
    }

    @Test
    fun `is order independent`() {
        assertEquals(1u, listOf(3.chapter, 1.chapter).missingChaptersCount())
    }

    // endregion

    // region calculateChapterGap — ChapterNumber overload

    @Test
    fun `gap counts whole chapters between two numbers`() {
        assertEquals(4u, calculateChapterGap(10.chapter, 5.chapter))
    }

    @Test
    fun `adjacent numbers have no gap`() {
        assertEquals(0u, calculateChapterGap(6.chapter, 5.chapter))
    }

    @Test
    fun `equal numbers have no gap`() {
        assertEquals(0u, calculateChapterGap(5.chapter, 5.chapter))
    }

    @Test
    fun `gap is zero when higher is below lower`() {
        assertEquals(0u, calculateChapterGap(5.chapter, 10.chapter))
    }

    @Test
    fun `gap collapses sub-chapters to whole chapters`() {
        assertEquals(4u, calculateChapterGap(10.5.chapter, 5.2.chapter))
    }

    // endregion

    // region calculateChapterGap — Chapter overload

    @Test
    fun `gap is zero when a chapter is null`() {
        assertEquals(0u, calculateChapterGap(null, chapterWith(5.chapter)))
        assertEquals(0u, calculateChapterGap(chapterWith(5.chapter), null))
    }

    @Test
    fun `gap is zero when a chapter has no recognized number`() {
        assertEquals(0u, calculateChapterGap(chapterWith(null), chapterWith(5.chapter)))
        assertEquals(0u, calculateChapterGap(chapterWith(10.chapter), chapterWith(null)))
    }

    @Test
    fun `gap between two chapters delegates to their numbers`() {
        assertEquals(4u, calculateChapterGap(chapterWith(10.chapter), chapterWith(5.chapter)))
    }

    // endregion
}