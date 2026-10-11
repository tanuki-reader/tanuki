package app.tanuki.features.bookmarks.viewmodel

import app.tanuki.core.domain.bookmark.model.PageBookmark
import app.tanuki.core.domain.bookmark.model.PageBookmarkEntry
import app.tanuki.core.domain.chapter.model.ChapterNumber
import app.tanuki.core.domain.chapter.model.PageImage
import app.tanuki.core.domain.common.model.Text
import app.tanuki.core.domain.common.model.Url
import app.tanuki.core.domain.manga.model.MangaCover
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlin.uuid.Uuid

class BookmarksGroupingTest {
    private val berserk = Uuid.random()
    private val vagabond = Uuid.random()

    @Test
    fun `series sit where their newest bookmark does and keep their own order`() {
        // Newest first, as the repository returns them: Berserk, Vagabond, Berserk.
        val entries =
            listOf(
                entry(berserk, "Berserk", page = 9, number = 12.0),
                entry(vagabond, "Vagabond", page = 1, number = 3.0),
                entry(berserk, "Berserk", page = 2, number = 10.5)
            )

        val series = group(entries)

        assertEquals(listOf("Berserk", "Vagabond"), series.map { it.title })
        assertEquals(listOf(9, 2), series[0].bookmarks.map { it.page })
        assertEquals(listOf("12", "10.5"), series[0].bookmarks.map { it.chapterNumberLabel })
        assertEquals("https://example.com/9.png", series[0].bookmarks[0].imageUrl)
        assertEquals(mapOf("Referer" to "https://example.com"), series[0].bookmarks[0].imageHeaders)
        assertNull(series[0].bookmarks[0].localPath)
        assertEquals("https://example.com/cover.jpg", series[0].coverUrl)
    }

    private fun entry(
        mangaId: Uuid,
        title: String,
        page: Int,
        number: Double
    ) = PageBookmarkEntry(
        bookmark =
            PageBookmark(
                id = Uuid.random(),
                mangaId = mangaId,
                chapterId = Uuid.random(),
                page = page,
                createdAt = Instant.DISTANT_PAST,
                image = PageImage.Remote(Url("https://example.com/$page.png"), mapOf("Referer" to "https://example.com"))
            ),
        mangaTitle = Text(title),
        cover = MangaCover(mangaId, Url("https://example.com/cover.jpg")),
        chapterNumber = ChapterNumber(number),
        chapterName = null
    )
}
