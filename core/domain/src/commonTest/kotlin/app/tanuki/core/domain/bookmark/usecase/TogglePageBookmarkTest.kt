package app.tanuki.core.domain.bookmark.usecase

import app.tanuki.core.domain.bookmark.model.PageBookmark
import app.tanuki.core.domain.bookmark.model.PageBookmarkEntry
import app.tanuki.core.domain.bookmark.repository.PageBookmarkRepository
import app.tanuki.core.domain.chapter.model.PageImage
import app.tanuki.core.domain.common.model.Url
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class TogglePageBookmarkTest {
    private val mangaId = Uuid.random()
    private val chapterId = Uuid.random()
    private val image = PageImage.Remote(Url("https://example.com/7.png"), mapOf("Referer" to "https://example.com"))

    @Test
    fun `the first toggle bookmarks the page with its image`() = runTest {
        val repository = InMemoryBookmarks()

        val bookmarked = TogglePageBookmark.Default(repository)(mangaId, chapterId, page = 7, image = image)

        assertTrue(bookmarked)
        val saved = repository.rows.single()
        assertEquals(Triple(mangaId, chapterId, 7), Triple(saved.mangaId, saved.chapterId, saved.page))
        assertEquals(image, saved.image)
    }

    @Test
    fun `the second toggle on the same page removes it and leaves other pages alone`() = runTest {
        val repository = InMemoryBookmarks()
        val toggle = TogglePageBookmark.Default(repository)
        toggle(mangaId, chapterId, page = 3, image = null)
        toggle(mangaId, chapterId, page = 7, image = image)

        val bookmarked = toggle(mangaId, chapterId, page = 7, image = image)

        assertFalse(bookmarked)
        assertEquals(listOf(3), repository.rows.map { it.page })
    }
}

private class InMemoryBookmarks : PageBookmarkRepository {
    val rows = mutableListOf<PageBookmark>()

    override fun observeForManga(mangaId: Uuid): Flow<List<PageBookmark>> = error("unused")

    override fun observeAll(): Flow<List<PageBookmarkEntry>> = error("unused")

    override suspend fun bookmarkedChapterIds(mangaId: Uuid): Set<Uuid> = rows.mapTo(mutableSetOf()) { it.chapterId }

    override suspend fun add(bookmark: PageBookmark) {
        rows.removeAll { it.chapterId == bookmark.chapterId && it.page == bookmark.page }
        rows += bookmark
    }

    override suspend fun removeAt(
        chapterId: Uuid,
        page: Int,
    ): Boolean = rows.removeAll { it.chapterId == chapterId && it.page == page }

    override suspend fun remove(id: Uuid) {
        rows.removeAll { it.id == id }
    }
}
