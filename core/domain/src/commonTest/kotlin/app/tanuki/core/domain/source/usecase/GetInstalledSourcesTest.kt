package app.tanuki.core.domain.source.usecase

import app.tanuki.core.domain.common.model.Text
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
import kotlin.test.assertNull

/**
 * Tests [GetInstalledSources] and [GetSourceMeta]. The behaviour worth pinning is the failure policy:
 * enumerating bundles is best-effort, so a failed read is an *empty* source list — never an error the
 * Home screen has to render, and never a stuck spinner.
 */
class GetInstalledSourcesTest {

    @Test
    fun `returns the runtime's installed sources`() = runTest {
        val sources = GetInstalledSources.Default(StubSourceLoader(listOf(META)))()

        assertEquals(listOf(META), sources)
    }

    @Test
    fun `swallows an enumeration failure as an empty list`() = runTest {
        val sources = GetInstalledSources.Default(StubSourceLoader(failure = IllegalStateException("bad bundle")))()

        assertEquals(emptyList(), sources)
    }

    @Test
    fun `resolves one source by its source-native id`() = runTest {
        val getMeta = GetSourceMeta.Default(GetInstalledSources.Default(StubSourceLoader(listOf(META))))

        assertEquals(META, getMeta("mangadex"))
    }

    @Test
    fun `yields null for a source that is not installed`() = runTest {
        val getMeta = GetSourceMeta.Default(GetInstalledSources.Default(StubSourceLoader(listOf(META))))

        assertNull(getMeta("comick"))
    }

    private companion object {
        val META = SourceMeta(id = "mangadex", name = Text("MangaDex"), lang = Text("en"), version = 1)
    }
}

/** A [SourceLoader] whose [listSources] returns [metas] or throws [failure]; other calls are unused. */
private class StubSourceLoader(
    private val metas: List<SourceMeta> = emptyList(),
    private val failure: Throwable? = null
) : SourceLoader {
    override suspend fun listSources(): List<SourceMeta> = failure?.let { throw it } ?: metas

    override suspend fun loadSource(id: String): Source = error("unused")
    override suspend fun fetchMangaList(sourceId: String, listing: SourceListing?, query: String?, page: Int): MangaPage =
        error("unused")
    override suspend fun fetchMangaDetails(sourceId: String, mangaKey: String): MangaListing = error("unused")
    override suspend fun fetchChapterList(sourceId: String, mangaKey: String) = error("unused")
    override suspend fun fetchChapterPages(sourceId: String, chapterId: String): List<Page> = error("unused")
}