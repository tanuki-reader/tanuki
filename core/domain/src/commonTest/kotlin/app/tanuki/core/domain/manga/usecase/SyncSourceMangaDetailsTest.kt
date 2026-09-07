package app.tanuki.core.domain.manga.usecase

import app.tanuki.core.domain.common.model.Text
import app.tanuki.core.domain.common.model.Url
import app.tanuki.core.domain.common.service.uuidV7
import app.tanuki.core.domain.manga.model.Manga
import app.tanuki.core.domain.manga.model.MangaCover
import app.tanuki.core.domain.manga.model.PublishStatus
import app.tanuki.core.domain.manga.model.ReadingMode
import app.tanuki.core.domain.manga.repository.MangaRepository
import app.tanuki.core.domain.source.SourceLoader
import app.tanuki.core.domain.source.model.ChapterListing
import app.tanuki.core.domain.source.model.MangaListing
import app.tanuki.core.domain.source.model.MangaPage
import app.tanuki.core.domain.source.model.Page
import app.tanuki.core.domain.source.model.Source
import app.tanuki.core.domain.source.model.SourceListing
import app.tanuki.core.domain.source.model.SourceMeta
import app.tanuki.core.domain.source.repository.SourceRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Tests [SyncSourceMangaDetails] against in-memory fakes.
 *
 * Two things are being pinned down. First, that a refresh is *additive*: a source that answers with a
 * blank field must never erase what is already stored, and a cover whose URL has not changed must keep
 * its `lastModified`, because that value is the image loader's cache key and re-stamping it would evict
 * an image that is still correct. Second, that the refresh is **gated and targeted** — it asks the
 * source at most once a day, it writes only the columns it owns rather than a whole row, and a failed
 * request leaves the stamp untouched so the next open retries.
 */
class SyncSourceMangaDetailsTest {
    @Test
    fun `stores the fields the source returned`() =
        runTest {
            val repository = DetailsMangaRepository(MANGA)
            val sync =
                sync(
                    repository,
                    details =
                        DETAILS.copy(
                            title = "One Piece",
                            author = "Eiichiro Oda",
                            description = "Pirates.",
                            status = PublishStatus.ONGOING
                        )
                )

            sync(MANGA.id)

            val written = repository.sourceDetails.single()
            assertEquals(MANGA.id, written.mangaId)
            assertEquals("One Piece", written.title.value)
            assertEquals("Eiichiro Oda", written.author?.value)
            assertEquals("Pirates.", written.description?.value)
            assertEquals(PublishStatus.ONGOING, written.publishStatus)
        }

    @Test
    fun `a blank field keeps what is already stored`() =
        runTest {
            val repository = DetailsMangaRepository(MANGA)
            // A detail endpoint that omits a field is likelier incomplete than authoritative — the
            // catalog entry these values came from is a legitimate source for them too.
            val sync = sync(repository, details = DETAILS.copy(title = "  ", author = null, description = ""))

            sync(MANGA.id)

            val written = repository.sourceDetails.single()
            assertEquals(MANGA.title.value, written.title.value)
            assertEquals(MANGA.author?.value, written.author?.value)
            assertEquals(MANGA.description?.value, written.description?.value)
        }

    @Test
    fun `an unchanged cover url keeps its cache key`() =
        runTest {
            val repository = DetailsMangaRepository(MANGA)
            val sync = sync(repository, details = DETAILS.copy(coverUrl = MANGA.cover.url))

            sync(MANGA.id)

            assertEquals(
                MANGA.cover.lastModified,
                repository.sourceDetails.single().cover.lastModified,
                "re-stamping would evict a cover the loader already holds, for no change in the art"
            )
        }

    @Test
    fun `a changed cover url re-stamps the cache key`() =
        runTest {
            val repository = DetailsMangaRepository(MANGA)
            val sync = sync(repository, details = DETAILS.copy(coverUrl = Url("https://cdn.example/new.jpg")))

            sync(MANGA.id)

            val cover = repository.sourceDetails.single().cover
            assertEquals("https://cdn.example/new.jpg", cover.url?.value)
            assertTrue(cover.lastModified > MANGA.cover.lastModified, "new art needs a new cache key")
        }

    @Test
    fun `the write is targeted rather than a whole row`() =
        runTest {
            val repository = DetailsMangaRepository(MANGA)

            sync(repository, details = DETAILS)(MANGA.id)

            // A whole-row upsert would be built from the snapshot read before the request, so it would
            // revert anything written while it was in flight — including the chapter sync the detail
            // screen fires at the same moment.
            assertTrue(repository.upserted.isEmpty())
            assertEquals(1, repository.sourceDetails.size)
        }

    // --- staleness gate --------------------------------------------------------------------------

    @Test
    fun `a series synced today is not asked again`() =
        runTest {
            val repository = DetailsMangaRepository(MANGA.copy(detailsFetchedAt = Clock.System.now()))
            val loader = RecordingSourceLoader(DETAILS)

            sync(repository, loader = loader)(MANGA.id)

            assertEquals(0, loader.detailFetches, "the source must not be asked inside the TTL")
            assertTrue(repository.sourceDetails.isEmpty(), "a suppressed refresh writes nothing at all")
        }

    @Test
    fun `a series synced long ago is asked again`() =
        runTest {
            val repository = DetailsMangaRepository(MANGA.copy(detailsFetchedAt = Clock.System.now() - 30.days))
            val loader = RecordingSourceLoader(DETAILS)

            sync(repository, loader = loader)(MANGA.id)

            assertEquals(1, loader.detailFetches)
        }

    @Test
    fun `a series that has never been synced is asked`() =
        runTest {
            val repository = DetailsMangaRepository(MANGA)
            val loader = RecordingSourceLoader(DETAILS)

            // No stamp is what every row carries right after the migration; it has to read as "ask now",
            // or upgrading would look like the app had stopped refreshing.
            sync(repository, loader = loader)(MANGA.id)

            assertEquals(1, loader.detailFetches)
        }

    @Test
    fun `force overrides a fresh stamp`() =
        runTest {
            val repository = DetailsMangaRepository(MANGA.copy(detailsFetchedAt = Clock.System.now()))
            val loader = RecordingSourceLoader(DETAILS)

            sync(repository, loader = loader)(MANGA.id, force = true)

            assertEquals(1, loader.detailFetches)
        }

    @Test
    fun `a completed refresh stamps the manga`() =
        runTest {
            val repository = DetailsMangaRepository(MANGA)

            sync(repository, details = DETAILS)(MANGA.id)

            assertEquals(MANGA.id, repository.detailsFetched.single().first)
        }

    @Test
    fun `a refresh that fails leaves the stamp alone`() =
        runTest {
            val repository = DetailsMangaRepository(MANGA)
            val loader = RecordingSourceLoader(DETAILS, failure = IllegalStateException("offline"))

            assertFailsWith<IllegalStateException> { sync(repository, loader = loader)(MANGA.id) }

            // Otherwise one failed refresh would suppress every retry for a whole day.
            assertTrue(repository.detailsFetched.isEmpty())
        }

    // --- no-ops ----------------------------------------------------------------------------------

    @Test
    fun `a manga with no source key is a no-op`() =
        runTest {
            val repository = DetailsMangaRepository(MANGA.copy(sourceMangaKey = null))
            val loader = RecordingSourceLoader(DETAILS)

            sync(repository, loader = loader)(MANGA.id)

            assertEquals(0, loader.detailFetches)
            assertTrue(repository.sourceDetails.isEmpty())
        }

    @Test
    fun `an unknown manga is a no-op`() =
        runTest {
            val repository = DetailsMangaRepository(null)
            val loader = RecordingSourceLoader(DETAILS)

            sync(repository, loader = loader)(uuidV7())

            assertEquals(0, loader.detailFetches)
        }

    // --- helpers ---------------------------------------------------------------------------------

    private fun sync(
        repository: DetailsMangaRepository,
        details: MangaListing = DETAILS,
        loader: RecordingSourceLoader = RecordingSourceLoader(details)
    ) = SyncSourceMangaDetails.Default(
        mangaRepository = repository,
        sourceRepository = SingleSourceRepository(SOURCE),
        sourceLoader = loader
    )

    private companion object {
        val SOURCE =
            Source(
                id = uuidV7(),
                sourceId = "mangadex",
                lang = Text("en"),
                name = Text("MangaDex"),
                url = Url("https://mangadex.org"),
                version = 2
            )

        val MANGA =
            Manga(
                id = uuidV7(),
                source = SOURCE.id,
                title = Text("One Piece"),
                author = Text("Oda"),
                description = Text("A cached synopsis."),
                cover =
                    MangaCover(
                        mangaId = uuidV7(),
                        url = Url("https://cdn.example/op.jpg"),
                        lastModified = Instant.fromEpochMilliseconds(1_000)
                    ),
                sourceMangaKey = Text("op-id"),
                url = Url("https://mangadex.org/title/op-id"),
                dateAdded = null,
                readingMode = ReadingMode.LEFT_TO_RIGHT,
                publishStatus = PublishStatus.UNKNOWN,
                readingStatus = null
            )

        val DETAILS = MangaListing(sourceMangaKey = "op-id", title = "One Piece")
    }
}

/** One targeted source-detail write, as [DetailsMangaRepository] captured it. */
private data class DetailWrite(
    val mangaId: Uuid,
    val title: Text,
    val author: Text?,
    val description: Text?,
    val publishStatus: PublishStatus,
    val cover: MangaCover
)

/** A [MangaRepository] observing one canned manga and recording every write it is asked for. */
private class DetailsMangaRepository(
    private val manga: Manga?
) : MangaRepository {
    val sourceDetails = mutableListOf<DetailWrite>()
    val detailsFetched = mutableListOf<Pair<Uuid, Instant>>()
    val upserted = mutableListOf<Manga>()

    override fun observeManga(mangaId: Uuid): Flow<Manga?> = flowOf(manga)

    override suspend fun setSourceDetails(
        mangaId: Uuid,
        title: Text,
        author: Text?,
        description: Text?,
        publishStatus: PublishStatus,
        cover: MangaCover
    ) {
        sourceDetails += DetailWrite(mangaId, title, author, description, publishStatus, cover)
    }

    override suspend fun markDetailsFetched(
        mangaId: Uuid,
        at: Instant
    ) {
        detailsFetched += mangaId to at
    }

    // Recorded rather than refused, so `the write is targeted` fails on a wrong call instead of on a
    // thrown error that any other bug could also produce.
    override suspend fun upsertManga(manga: Manga) {
        upserted += manga
    }

    override suspend fun getMangaByIds(mangaIds: Collection<Uuid>): List<Manga> = error("unused")

    override fun observeLibrary(): Flow<List<Manga>> = error("unused")

    override fun observeMangaBySource(sourceId: Uuid): Flow<List<Manga>> = error("unused")

    override suspend fun getMangaIdBySourceAndKey(
        sourceId: Uuid,
        sourceMangaKey: String
    ): Uuid? = error("unused")

    override suspend fun setCoverHeaders(
        mangaId: Uuid,
        headers: Map<String, String>
    ) = error("unused")

    override suspend fun recordChapterSync(
        mangaId: Uuid,
        at: Instant,
        nextUpdateAt: Instant,
        fetchInterval: Int
    ) = error("unused")

    override suspend fun setFilteredScanlators(
        mangaId: Uuid,
        scanlators: Set<String>
    ) = error("unused")

    override suspend fun setReadingStatus(
        mangaId: Uuid,
        status: app.tanuki.core.domain.manga.model.ReadingStatus
    ) = error("unused")

    override suspend fun setReadingMode(
        mangaId: Uuid,
        mode: ReadingMode
    ) = error("unused")

    override suspend fun deleteManga(mangaId: Uuid) = error("unused")

    override suspend fun addToLibrary(mangaId: Uuid) = error("unused")

    override suspend fun removeFromLibrary(mangaId: Uuid) = error("unused")
}

/** A [SourceLoader] with canned details that counts how often it was asked for them. */
private class RecordingSourceLoader(
    private val details: MangaListing,
    private val failure: Throwable? = null
) : SourceLoader {
    var detailFetches: Int = 0
        private set

    override suspend fun fetchMangaDetails(
        sourceId: String,
        mangaKey: String
    ): MangaListing {
        detailFetches++
        failure?.let { throw it }
        return details
    }

    override suspend fun loadSource(id: String): Source = error("unused")

    override fun observeInstalledSources(): Flow<List<SourceMeta>> = error("unused")

    override suspend fun fetchMangaList(
        sourceId: String,
        listing: SourceListing?,
        query: String?,
        page: Int
    ): MangaPage = error("unused")

    override suspend fun fetchChapterList(
        sourceId: String,
        mangaKey: String
    ): List<ChapterListing> = error("unused")

    override suspend fun fetchChapterPages(
        sourceId: String,
        chapterId: String
    ): List<Page> = error("unused")
}

/** A [SourceRepository] that resolves every id to one canned source. */
private class SingleSourceRepository(
    private val source: Source
) : SourceRepository {
    override suspend fun getSource(id: Uuid): Source? = source

    override suspend fun getSourceByNativeId(sourceId: String): Source? = source

    override fun observeSources(): Flow<List<Source>> = flowOf(listOf(source))

    override suspend fun addSource(source: Source) = error("unused")

    override suspend fun deleteSource(source: Source) = error("unused")
}
