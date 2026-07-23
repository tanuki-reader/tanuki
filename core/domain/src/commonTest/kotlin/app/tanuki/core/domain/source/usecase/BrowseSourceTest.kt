package app.tanuki.core.domain.source.usecase

import app.tanuki.core.domain.source.SourceLoader
import app.tanuki.core.domain.source.model.MangaListing
import app.tanuki.core.domain.source.model.MangaPage
import app.tanuki.core.domain.source.model.Page
import app.tanuki.core.domain.source.model.Source
import app.tanuki.core.domain.source.model.SourceListing
import app.tanuki.core.domain.source.model.SourceMeta
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Tests [BrowseSource] — the catalog-fetch use case that owns the ABI's search-overrides-listing
 * precedence. What matters is the *arguments it hands the loader*, since that rule used to live in the
 * browse ViewModel and is what callers now delegate.
 */
class BrowseSourceTest {

    @Test
    fun `browses the selected listing when there is no query`() = runTest {
        val loader = RecordingSourceLoader()

        BrowseSource.Default(loader)("mangadex", listing = SourceListing.POPULAR, query = null, page = 2)

        val call = loader.calls.single()
        assertEquals("mangadex", call.sourceId)
        assertEquals(SourceListing.POPULAR, call.listing)
        assertNull(call.query)
        assertEquals(2, call.page)
    }

    @Test
    fun `a search drops the listing since the source cannot combine them`() = runTest {
        val loader = RecordingSourceLoader()

        BrowseSource.Default(loader)("mangadex", listing = SourceListing.POPULAR, query = "one piece")

        val call = loader.calls.single()
        assertNull(call.listing, "a query must replace the listing, not filter it")
        assertEquals("one piece", call.query)
    }

    @Test
    fun `a blank query browses rather than searching for nothing`() = runTest {
        val loader = RecordingSourceLoader()

        BrowseSource.Default(loader)("mangadex", listing = SourceListing.POPULAR, query = "   ")

        val call = loader.calls.single()
        assertEquals(SourceListing.POPULAR, call.listing)
        assertNull(call.query)
    }

    @Test
    fun `propagates a fetch failure so the browse screen can offer a retry`() = runTest {
        val loader = RecordingSourceLoader(failure = IllegalStateException("offline"))

        assertFailsWith<IllegalStateException> { BrowseSource.Default(loader)("mangadex") }
    }
}

/** A [SourceLoader] recording every [fetchMangaList] call; other calls are unused here. */
private class RecordingSourceLoader(private val failure: Throwable? = null) : SourceLoader {
    data class Call(val sourceId: String, val listing: SourceListing?, val query: String?, val page: Int)

    val calls = mutableListOf<Call>()

    override suspend fun fetchMangaList(sourceId: String, listing: SourceListing?, query: String?, page: Int): MangaPage {
        calls += Call(sourceId, listing, query, page)
        failure?.let { throw it }
        return MangaPage()
    }

    override suspend fun loadSource(id: String): Source = error("unused")
    override suspend fun listSources(): List<SourceMeta> = error("unused")
    override suspend fun fetchMangaDetails(sourceId: String, mangaKey: String): MangaListing = error("unused")
    override suspend fun fetchChapterList(sourceId: String, mangaKey: String) = error("unused")
    override suspend fun fetchChapterPages(sourceId: String, chapterId: String): List<Page> = error("unused")
}