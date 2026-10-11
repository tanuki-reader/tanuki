package app.tanuki.core.domain.metadata.usecase

import app.tanuki.core.domain.common.model.Text
import app.tanuki.core.domain.common.model.Url
import app.tanuki.core.domain.common.service.LogPriority
import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.domain.download.usecase.FakeMangaRepository
import app.tanuki.core.domain.download.usecase.FakeSourceRepository
import app.tanuki.core.domain.manga.model.Manga
import app.tanuki.core.domain.manga.model.MangaCover
import app.tanuki.core.domain.manga.model.PublishStatus
import app.tanuki.core.domain.manga.model.ReadingMode
import app.tanuki.core.domain.metadata.LocalizedText
import app.tanuki.core.domain.metadata.LocalizedTextProvider
import app.tanuki.core.domain.metadata.MetadataProvider
import app.tanuki.core.domain.metadata.MetadataRepository
import app.tanuki.core.domain.metadata.TitleCandidate
import app.tanuki.core.domain.metadata.TitleLookup
import app.tanuki.core.domain.metadata.model.MatchedBy
import app.tanuki.core.domain.metadata.model.MetadataSearchResult
import app.tanuki.core.domain.metadata.model.SeriesMetadata
import app.tanuki.core.domain.metadata.model.TrackerLink
import app.tanuki.core.domain.source.SourceLoader
import app.tanuki.core.domain.source.model.MangaListing
import app.tanuki.core.domain.source.model.MangaPage
import app.tanuki.core.domain.source.model.Page
import app.tanuki.core.domain.source.model.Source
import app.tanuki.core.domain.source.model.SourceListing
import app.tanuki.core.domain.source.model.SourceMeta
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

/**
 * Enrichment is a cache, keyed by series and language: one fetch serves every library copy of a series in
 * one language, and opening a page again inside the freshness window costs no network at all. What it
 * learns lands on the `manga` row (cover, reading mode) unless the user chose otherwise.
 */
class EnrichMangaTest {
    private val source = Source(Uuid.random(), "mangadex", Text("en"), Text("MangaDex"), Url("https://mangadex.org"))
    private val ptSource = Source(Uuid.random(), "mangafire-pt-br", Text("pt-BR"), Text("MangaFire"), Url("https://mangafire.to"))

    @Test
    fun `the first open links from the source ids and fetches once`() = runTest {
        val world = World()
        val manga = world.manga("op")

        world.enrich(manga.id)

        assertEquals(30013L, world.repository.links.value[manga.id]?.anilistId)
        assertEquals(1, world.provider.calls)
        assertEquals(1, world.loader.detailCalls)
        assertEquals(setOf(30013L to "en"), world.repository.metadata.value.keys)
    }

    @Test
    fun `reopening inside the freshness window touches neither the source nor the provider`() = runTest {
        val world = World()
        val manga = world.manga("op")
        world.enrich(manga.id)

        repeat(3) { world.enrich(manga.id) }

        assertEquals(1, world.provider.calls)
        assertEquals(1, world.loader.detailCalls)
    }

    @Test
    fun `two copies of a series share one metadata row and one fetch`() = runTest {
        val world = World()
        val fromMangaDex = world.manga("op")
        val fromOtherSource = world.manga("op-elsewhere")

        world.enrich(fromMangaDex.id)
        world.enrich(fromOtherSource.id)

        // Each copy has its own link (one per manga) pointing at the same series...
        assertEquals(listOf(30013L, 30013L), world.repository.links.value.values.map { it.anilistId })
        // ...and the series' metadata is one row, fetched once.
        assertEquals(1, world.repository.metadata.value.size)
        assertEquals(1, world.provider.calls)
    }

    @Test
    fun `each language gets its own row - with MangaDex text in it or none`() = runTest {
        val world = World()
        val english = world.manga("op")
        val portuguese = world.manga("op-pt", ptSource)

        world.enrich(english.id)
        world.enrich(portuguese.id)

        assertEquals(setOf(30013L to "en", 30013L to "pt-br"), world.repository.metadata.value.keys)
        assertEquals(2, world.provider.calls)
        val pt = world.repository.metadata.value.getValue(30013L to "pt-br")
        assertEquals("Uma Peça" to "Sinopse", pt.title to pt.synopsis)
        assertEquals("md-op" to "pt-br", world.localized.asked.single())
        // Neutral data is the same in both.
        assertEquals(world.repository.metadata.value.getValue(30013L to "en").score, pt.score)

        // No Portuguese text on MangaDex: the source's own shows.
        world.localized.text = null
        val other = world.manga("other-pt", ptSource)
        world.repository.metadata.value = emptyMap()
        world.enrich(other.id)
        val bare = world.repository.metadata.value.getValue(30013L to "pt-br")
        assertEquals(null to null, bare.title to bare.synopsis)
    }

    @Test
    fun `the metadata's cover and reading mode reach the manga row`() = runTest {
        val world = World()
        world.provider.cover = "https://anilist.co/op.jpg"
        world.provider.country = "KR"
        val manga = world.manga("op")

        world.enrich(manga.id)

        val stored = world.stored(manga.id)
        assertEquals("https://anilist.co/op.jpg", stored.cover.url?.value)
        assertEquals("https://mangadex.org/op.jpg", stored.cover.sourceUrl?.value)
        assertEquals(ReadingMode.WEBTOON, stored.readingMode)
    }

    @Test
    fun `a reading mode the user chose is never replaced`() = runTest {
        val world = World()
        world.provider.country = "JP"
        val manga = world.manga("op")
        world.mangaRepository.setReadingMode(manga.id, ReadingMode.VERTICAL)

        world.enrich(manga.id, force = true)

        assertEquals(ReadingMode.VERTICAL, world.stored(manga.id).readingMode)
    }

    @Test
    fun `stale metadata is fetched again`() = runTest {
        val world = World()
        val manga = world.manga("op")
        world.enrich(manga.id)
        val key = 30013L to "en"
        val cached = world.repository.metadata.value.getValue(key)
        world.repository.metadata.value = mapOf(key to cached.copy(fetchedAt = Clock.System.now() - 8.days))

        world.enrich(manga.id)

        assertEquals(2, world.provider.calls)
    }

    @Test
    fun `a source without ids links by an exact AniList title - case and punctuation aside`() = runTest {
        val world = World()
        world.loader.publishesIds = false
        world.provider.searchResults = listOf(result(1, "Sex Study Group", "Sextudy Group"), result(2, "Sextudy Group 2"))
        val manga = world.manga("sg", title = "SEXTUDY GROUP!")

        world.enrich(manga.id)

        val link = world.repository.links.value.getValue(manga.id)
        assertEquals(1L to MatchedBy.Title, link.anilistId to link.matchedBy)
        assertEquals(0, world.titleLookup.calls)
        assertEquals(1, world.provider.calls)
    }

    @Test
    fun `no exact AniList match falls back to MangaDex titles and ids`() = runTest {
        val world = World()
        world.loader.publishesIds = false
        world.provider.searchResults = listOf(result(1, "Sextudy Group Season 2"))
        world.titleLookup.candidates =
            listOf(
                TitleCandidate(listOf("Something Else", "Sextudy Group"), anilistId = 145493, malId = 7, mangaDexId = "md-sg"),
                TitleCandidate(listOf("Sextudy Group"), anilistId = null, malId = null, mangaDexId = "md-no-ids")
            )
        val manga = world.manga("sg", ptSource, title = "Sextudy Group")

        world.enrich(manga.id)

        val link = world.repository.links.value.getValue(manga.id)
        assertEquals(Triple(145493L, 7L, "md-sg"), Triple(link.anilistId, link.malId, link.mangaDexId))
        assertEquals(MatchedBy.Title, link.matchedBy)
    }

    @Test
    fun `an ambiguous or missing title stays unmatched and is not searched again`() = runTest {
        val world = World()
        world.loader.publishesIds = false
        world.provider.searchResults = listOf(result(1, "Sextudy Group"), result(2, "Sextudy Group"))
        val manga = world.manga("sg", title = "Sextudy Group")

        world.enrich(manga.id)
        world.enrich(manga.id)

        assertEquals(MatchedBy.Unmatched, world.repository.links.value.getValue(manga.id).matchedBy)
        assertEquals(1, world.provider.searchCalls)
        assertEquals(1, world.titleLookup.calls)
        assertEquals(0, world.provider.calls)
    }

    @Test
    fun `a series the user unlinked is never matched by title`() = runTest {
        val world = World()
        world.provider.searchResults = listOf(result(1, "One Piece"))
        val manga = world.manga("op")
        world.repository.upsertLink(TrackerLink(manga.id, null, null, null, MatchedBy.User))

        world.enrich(manga.id)

        assertEquals(0, world.provider.searchCalls)
        assertEquals(null, world.repository.links.value.getValue(manga.id).anilistId)
    }

    @Test
    fun `a provider failure is swallowed`() = runTest {
        val world = World()
        world.provider.failing = true
        val manga = world.manga("op")

        world.enrich(manga.id) // must not throw

        assertEquals(emptyMap(), world.repository.metadata.value)
    }

    private inner class World {
        val repository = MemoryMetadataRepository()
        val provider = CountingProvider()
        val loader = DetailsLoader()
        val localized = RecordingLocalizedText()
        val titleLookup = FakeTitleLookup()
        private var held: FakeMangaRepository? = null
        val mangaRepository: FakeMangaRepository get() = checkNotNull(held)

        fun manga(
            key: String,
            from: Source = source,
            title: String = "One Piece"
        ): Manga {
            val id = Uuid.random()
            val manga =
                Manga(
                    id = id,
                    source = from.id,
                    title = Text(title),
                    author = null,
                    description = null,
                    cover = MangaCover(id, Url("https://mangadex.org/op.jpg")),
                    sourceMangaKey = Text(key),
                    url = Url("https://mangadex.org/title/$key"),
                    dateAdded = null,
                    readingMode = ReadingMode.LEFT_TO_RIGHT,
                    publishStatus = PublishStatus.ONGOING,
                    readingStatus = null
                )
            held?.add(manga) ?: run { held = FakeMangaRepository(manga) }
            return manga
        }

        suspend fun stored(mangaId: Uuid): Manga = checkNotNull(mangaRepository.observeManga(mangaId).first())

        suspend fun enrich(
            mangaId: Uuid,
            force: Boolean = false
        ) {
            EnrichManga.Default(
                mangaRepository = mangaRepository,
                sourceRepository = FakeSourceRepository(source, ptSource),
                sourceLoader = loader,
                metadataRepository = repository,
                provider = provider,
                localizedText = localized,
                titleLookup = titleLookup,
                applyMangaMetadata = ApplyMangaMetadata.Default(mangaRepository, repository),
                logger = SilentLogger
            )(mangaId, force)
        }
    }
}

internal class MemoryMetadataRepository : MetadataRepository {
    val links = MutableStateFlow<Map<Uuid, TrackerLink>>(emptyMap())
    val metadata = MutableStateFlow<Map<Pair<Long, String>, SeriesMetadata>>(emptyMap())

    override fun observeLink(mangaId: Uuid): Flow<TrackerLink?> = links.map { it[mangaId] }

    override suspend fun getLink(mangaId: Uuid): TrackerLink? = links.value[mangaId]

    override suspend fun upsertLink(link: TrackerLink) {
        links.value += (link.mangaId to link)
    }

    override suspend fun deleteLink(mangaId: Uuid) {
        links.value -= mangaId
    }

    override fun observeMetadata(anilistId: Long, lang: String): Flow<SeriesMetadata?> =
        metadata.map { it[anilistId to lang] }

    override suspend fun getMetadata(anilistId: Long, lang: String): SeriesMetadata? = metadata.value[anilistId to lang]

    override suspend fun upsertMetadata(metadata: SeriesMetadata) {
        this.metadata.value += ((metadata.anilistId to metadata.lang) to metadata)
    }
}

internal class CountingProvider : MetadataProvider {
    var calls = 0
    var failing = false
    var cover: String? = null
    var country: String? = null

    override suspend fun byAniListId(id: Long): SeriesMetadata? {
        calls++
        if (failing) error("AniList is down")
        return SeriesMetadata(
            anilistId = id, malId = 13, title = "One Piece", synopsis = null, coverUrl = cover, bannerUrl = null,
            score = 88, popularity = 1, genres = listOf("Action"), tags = emptyList(), status = PublishStatus.ONGOING,
            year = 1997, characters = emptyList(), reviews = emptyList(), recommendations = emptyList(),
            siteUrl = "https://anilist.co/manga/$id", fetchedAt = Clock.System.now(), countryOfOrigin = country
        )
    }

    override suspend fun byMalId(id: Long): SeriesMetadata? = byAniListId(30013)

    var searchResults: List<MetadataSearchResult> = emptyList()
    var searchCalls = 0

    override suspend fun search(title: String): List<MetadataSearchResult> {
        searchCalls++
        return searchResults
    }
}

private fun result(
    anilistId: Long,
    vararg titles: String
) = MetadataSearchResult(anilistId, titles.first(), coverUrl = null, year = null, format = null, titles = titles.toList())

private class FakeTitleLookup : TitleLookup {
    var candidates: List<TitleCandidate> = emptyList()
    var calls = 0

    override suspend fun byTitle(title: String): List<TitleCandidate> {
        calls++
        return candidates
    }
}

private class RecordingLocalizedText : LocalizedTextProvider {
    var text: LocalizedText? = LocalizedText("Uma Peça", "Sinopse")
    val asked = mutableListOf<Pair<String, String>>()

    override suspend fun localized(mangaDexId: String, lang: String): LocalizedText? {
        asked += mangaDexId to lang
        return text
    }
}

/** Every series' details name the same AniList/MAL ids — the source-published identity. */
private class DetailsLoader : SourceLoader {
    var detailCalls = 0
    var publishesIds = true

    override suspend fun fetchMangaDetails(sourceId: String, mangaKey: String): MangaListing {
        detailCalls++
        return if (publishesIds) MangaListing(mangaKey, title = "One Piece", anilistId = 30013, malId = 13, mangaDexId = "md-op")
        else MangaListing(mangaKey, title = "One Piece")
    }

    override suspend fun loadSource(id: String): Source = error("unused")
    override fun observeInstalledSources(): Flow<List<SourceMeta>> = error("unused")
    override suspend fun fetchMangaList(sourceId: String, listing: SourceListing?, query: String?, page: Int): MangaPage = error("unused")
    override suspend fun fetchChapterList(sourceId: String, mangaKey: String) = error("unused")
    override suspend fun fetchChapterPages(sourceId: String, chapterId: String): List<Page> = error("unused")
}

private object SilentLogger : Logger {
    override fun log(
        priority: LogPriority,
        tag: String?,
        message: String?,
        throwable: Throwable?,
        analyticsEvent: String?,
        addToBreadcrumbs: Boolean
    ) = Unit

    override fun addReceiver(receiver: Logger.LogReceiver) = Unit

    override fun removeReceiver(receiver: Logger.LogReceiver) = Unit
}
