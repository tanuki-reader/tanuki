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
import app.tanuki.core.domain.metadata.MetadataProvider
import app.tanuki.core.domain.metadata.MetadataRepository
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

/**
 * Enrichment is a cache, keyed by series: one fetch serves every library copy of a series, and opening a
 * page again inside the freshness window costs no network at all.
 */
class EnrichMangaTest {
    private val source = Source(Uuid.random(), "mangadex", Text("en"), Text("MangaDex"), Url("https://mangadex.org"))

    @Test
    fun `the first open links from the source ids and fetches once`() = runTest {
        val world = World()
        val manga = world.manga("op")

        world.enrich(manga.id)

        assertEquals(30013L, world.repository.links.value[manga.id]?.anilistId)
        assertEquals(1, world.provider.calls)
        assertEquals(1, world.loader.detailCalls)
        assertEquals(setOf(30013L), world.repository.metadata.value.keys)
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
    fun `stale metadata is fetched again`() = runTest {
        val world = World()
        val manga = world.manga("op")
        world.enrich(manga.id)
        val cached = world.repository.metadata.value.getValue(30013L)
        world.repository.metadata.value = mapOf(30013L to cached.copy(fetchedAt = Clock.System.now() - 8.days))

        world.enrich(manga.id)

        assertEquals(2, world.provider.calls)
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
        private val mangas = mutableListOf<Manga>()

        fun manga(key: String): Manga {
            val id = Uuid.random()
            val manga =
                Manga(
                    id = id,
                    source = source.id,
                    title = Text("One Piece"),
                    author = null,
                    description = null,
                    cover = MangaCover(id, null),
                    sourceMangaKey = Text(key),
                    url = Url("https://mangadex.org/title/$key"),
                    dateAdded = null,
                    readingMode = ReadingMode.RIGHT_TO_LEFT,
                    publishStatus = PublishStatus.ONGOING,
                    readingStatus = null
                )
            mangas += manga
            return manga
        }

        suspend fun enrich(mangaId: Uuid) {
            val manga = mangas.single { it.id == mangaId }
            EnrichManga.Default(
                mangaRepository = FakeMangaRepository(manga),
                sourceRepository = FakeSourceRepository(source),
                sourceLoader = loader,
                metadataRepository = repository,
                provider = provider,
                logger = SilentLogger
            )(mangaId)
        }
    }
}

internal class MemoryMetadataRepository : MetadataRepository {
    val links = MutableStateFlow<Map<Uuid, TrackerLink>>(emptyMap())
    val metadata = MutableStateFlow<Map<Long, SeriesMetadata>>(emptyMap())

    override fun observeLink(mangaId: Uuid): Flow<TrackerLink?> = links.map { it[mangaId] }

    override suspend fun getLink(mangaId: Uuid): TrackerLink? = links.value[mangaId]

    override suspend fun upsertLink(link: TrackerLink) {
        links.value += (link.mangaId to link)
    }

    override suspend fun deleteLink(mangaId: Uuid) {
        links.value -= mangaId
    }

    override fun observeMetadata(anilistId: Long): Flow<SeriesMetadata?> = metadata.map { it[anilistId] }

    override suspend fun getMetadata(anilistId: Long): SeriesMetadata? = metadata.value[anilistId]

    override suspend fun upsertMetadata(metadata: SeriesMetadata) {
        this.metadata.value += (metadata.anilistId to metadata)
    }
}

private class CountingProvider : MetadataProvider {
    var calls = 0
    var failing = false

    override suspend fun byAniListId(id: Long): SeriesMetadata? {
        calls++
        if (failing) error("AniList is down")
        return SeriesMetadata(
            anilistId = id, malId = 13, title = "One Piece", synopsis = null, coverUrl = null, bannerUrl = null,
            score = 88, popularity = 1, genres = listOf("Action"), tags = emptyList(), status = PublishStatus.ONGOING,
            year = 1997, characters = emptyList(), reviews = emptyList(), recommendations = emptyList(),
            siteUrl = "https://anilist.co/manga/$id", fetchedAt = Clock.System.now()
        )
    }

    override suspend fun byMalId(id: Long): SeriesMetadata? = byAniListId(30013)

    override suspend fun search(title: String): List<MetadataSearchResult> = emptyList()
}

/** Every series' details name the same AniList/MAL ids — the source-published identity. */
private class DetailsLoader : SourceLoader {
    var detailCalls = 0

    override suspend fun fetchMangaDetails(sourceId: String, mangaKey: String): MangaListing {
        detailCalls++
        return MangaListing(mangaKey, title = "One Piece", anilistId = 30013, malId = 13)
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
