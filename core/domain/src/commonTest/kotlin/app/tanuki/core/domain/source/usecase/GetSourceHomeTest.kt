package app.tanuki.core.domain.source.usecase

import app.tanuki.core.domain.source.SourceLoader
import app.tanuki.core.domain.source.model.MangaListing
import app.tanuki.core.domain.source.model.MangaPage
import app.tanuki.core.domain.source.model.Page
import app.tanuki.core.domain.source.model.Source
import app.tanuki.core.domain.source.model.SourceHome
import app.tanuki.core.domain.source.model.SourceHomeSection
import app.tanuki.core.domain.source.model.SourceListing
import app.tanuki.core.domain.source.model.SourceListing.LATEST
import app.tanuki.core.domain.source.model.SourceListing.POPULAR
import app.tanuki.core.domain.source.model.SourceMeta
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class GetSourceHomeTest {
    @Test
    fun `without a home every declared listing and the default catalog are fetched`() = runTest {
        val loader = HomeSourceLoader(home = null)

        val rails = GetSourceHome.Default(loader, BrowseSource.Default(loader))("src", listOf(POPULAR, LATEST))

        assertEquals(listOf(POPULAR, LATEST, null), rails.map { it.listing })
        assertEquals(listOf("popular-1", "latest-1", "default-1"), rails.map { it.entries!!.single().title })
    }

    @Test
    fun `a home's sections are used and only the missing rails are fetched`() = runTest {
        val home = SourceHome(listOf(SourceHomeSection(POPULAR, listOf(MangaListing("k", title = "from-home")))))
        val loader = HomeSourceLoader(home = home)

        val rails = GetSourceHome.Default(loader, BrowseSource.Default(loader))("src", listOf(POPULAR, LATEST))

        assertEquals(listOf("from-home", "latest-1", "default-1"), rails.map { it.entries!!.single().title })
        assertEquals(listOf(LATEST, null), loader.fetched)
    }

    @Test
    fun `a failed listing comes back empty-handed without failing the others`() = runTest {
        val loader = HomeSourceLoader(home = null, failing = LATEST)

        val rails = GetSourceHome.Default(loader, BrowseSource.Default(loader))("src", listOf(POPULAR, LATEST))

        assertEquals(listOf(true, false, true), rails.map { it.entries != null })
    }
}

private class HomeSourceLoader(
    private val home: SourceHome?,
    private val failing: SourceListing? = null
) : SourceLoader {
    val fetched = mutableListOf<SourceListing?>()

    override suspend fun fetchSourceHome(sourceId: String): SourceHome? = home

    override suspend fun fetchMangaList(sourceId: String, listing: SourceListing?, query: String?, page: Int): MangaPage {
        fetched += listing
        if (listing == failing && failing != null) error("boom")
        val name = listing?.wireName ?: "default"
        return MangaPage(entries = listOf(MangaListing("k-$name", title = "$name-$page")), hasNextPage = false)
    }

    override suspend fun loadSource(id: String): Source = error("unused")
    override fun observeInstalledSources(): Flow<List<SourceMeta>> = error("unused")
    override suspend fun fetchMangaDetails(sourceId: String, mangaKey: String): MangaListing = error("unused")
    override suspend fun fetchChapterList(sourceId: String, mangaKey: String) = error("unused")
    override suspend fun fetchChapterPages(sourceId: String, chapterId: String): List<Page> = error("unused")
}
