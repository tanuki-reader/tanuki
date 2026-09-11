package app.tanuki.core.domain.manga.usecase

import app.tanuki.core.domain.common.model.Text
import app.tanuki.core.domain.common.model.Url
import app.tanuki.core.domain.common.service.uuidV7
import app.tanuki.core.domain.library.model.LibraryManga
import app.tanuki.core.domain.manga.model.Manga
import app.tanuki.core.domain.manga.model.MangaCover
import app.tanuki.core.domain.manga.model.PublishStatus
import app.tanuki.core.domain.manga.model.ReadingMode
import app.tanuki.core.domain.manga.model.ReadingStatus
import app.tanuki.core.domain.manga.repository.MangaRepository
import app.tanuki.core.domain.source.SourceLoader
import app.tanuki.core.domain.source.model.MangaListing
import app.tanuki.core.domain.source.model.MangaPage
import app.tanuki.core.domain.source.model.Page
import app.tanuki.core.domain.source.model.Source
import app.tanuki.core.domain.source.model.SourceListing
import app.tanuki.core.domain.source.model.SourceMeta
import app.tanuki.core.domain.update.model.LibraryUpdateTarget
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Tests [CacheSourceManga] — the browse-tap → persisted-manga inflation — against in-memory fakes.
 * Two behaviours matter: a new listing is written once with no library membership, and a listing that's
 * already cached resolves to the same row *without* a rewrite (which could clobber a library entry).
 */
class CacheSourceMangaTest {

    @Test
    fun `caches a new listing as a library-less manga and returns its id`() = runTest {
        val repository = FakeMangaRepository()
        val cache = CacheSourceManga.Default(FakeSourceLoader(SOURCE), repository)

        val id = cache("mangadex", listing("op", "https://mangadex.org/title/op", "One Piece"))

        val stored = repository.upserted.single()
        assertEquals(id, stored.id)
        assertEquals(SOURCE.id, stored.source)
        assertEquals("One Piece", stored.title.value)
        assertEquals("op", stored.sourceMangaKey?.value)
        assertEquals("https://mangadex.org/title/op", stored.url.value)
        // Cache-only: not in the library until the user adds it.
        assertNull(stored.dateAdded)
    }

    @Test
    fun `falls back to the source base url when the listing has no permalink`() = runTest {
        val repository = FakeMangaRepository()
        val cache = CacheSourceManga.Default(FakeSourceLoader(SOURCE), repository)

        cache("mangadex", listing("op", url = null, title = "One Piece"))

        assertEquals(SOURCE.url.value, repository.upserted.single().url.value)
    }

    @Test
    fun `reuses the existing row and does not rewrite an already-cached listing`() = runTest {
        val existing = uuidV7()
        val repository = FakeMangaRepository(existingId = existing)
        val cache = CacheSourceManga.Default(FakeSourceLoader(SOURCE), repository)

        val id = cache("mangadex", listing("op", "https://mangadex.org/title/op", "One Piece"))

        assertEquals(existing, id)
        assertEquals(0, repository.upserted.size, "an already-cached manga must not be re-upserted")
    }

    @Test
    fun `an already-cached row adopts cover headers the source has since added`() = runTest {
        val existing = cached(headers = emptyMap())
        val repository = FakeMangaRepository(existingId = existing.id, existing = existing)
        val cache = CacheSourceManga.Default(FakeSourceLoader(SOURCE), repository)

        cache(
            "mangadex",
            listing("op", "https://mangadex.org/title/op", "One Piece")
                .copy(coverHeaders = mapOf("Referer" to "https://lerhentais.com/"))
        )

        // Without this a row cached before its source gained the headers renders as a blank tile
        // forever — its CDN 403s any request without them.
        val (id, headers) = repository.coverHeaders.single()
        assertEquals(existing.id, id)
        assertEquals(mapOf("Referer" to "https://lerhentais.com/"), headers)
        // Targeted, not a whole-row rewrite: browse can be caching this listing while the detail screen
        // syncs the same series, and a full write would revert that sync and its fetch stamps.
        assertEquals(0, repository.upserted.size)
    }

    @Test
    fun `an already-cached row with the same headers is not written at all`() = runTest {
        val headers = mapOf("Referer" to "https://lerhentais.com/")
        val existing = cached(headers = headers)
        val repository = FakeMangaRepository(existingId = existing.id, existing = existing)
        val cache = CacheSourceManga.Default(FakeSourceLoader(SOURCE), repository)

        cache("mangadex", listing("op", "https://mangadex.org/title/op", "One Piece").copy(coverHeaders = headers))

        // The overwhelmingly common case is a read and nothing else.
        assertEquals(0, repository.coverHeaders.size)
    }

    private fun cached(headers: Map<String, String>): Manga {
        val id = uuidV7()
        return Manga(
            id = id,
            source = SOURCE.id,
            title = Text("One Piece"),
            author = null,
            description = null,
            cover =
                MangaCover(
                    mangaId = id,
                    url = Url("https://cdn.example/op.jpg"),
                    headers = headers,
                ),
            sourceMangaKey = Text("op"),
            url = Url("https://mangadex.org/title/op"),
            dateAdded = null,
            readingMode = ReadingMode.LEFT_TO_RIGHT,
            publishStatus = PublishStatus.ONGOING,
            readingStatus = null,
        )
    }

    private fun listing(key: String, url: String?, title: String) = MangaListing(
        sourceMangaKey = key,
        url = url?.let(::Url),
        title = title,
        coverUrl = null,
        author = "Oda",
        description = "Pirates.",
        status = PublishStatus.ONGOING,
    )

    private companion object {
        val SOURCE = Source(
            id = uuidV7(),
            sourceId = "mangadex",
            lang = Text("en"),
            name = Text("MangaDex"),
            url = Url("https://mangadex.org"),
            version = 1,
        )
    }
}

/** A [SourceLoader] that returns one canned [Source] from [loadSource]; other calls are unused here. */
private class FakeSourceLoader(private val source: Source) : SourceLoader {
    override suspend fun loadSource(id: String): Source = source
    override fun observeInstalledSources(): Flow<List<SourceMeta>> = error("unused")
    override suspend fun fetchMangaList(sourceId: String, listing: SourceListing?, query: String?, page: Int): MangaPage =
        error("unused")
    override suspend fun fetchMangaDetails(sourceId: String, mangaKey: String): MangaListing = error("unused")
    override suspend fun fetchChapterList(sourceId: String, mangaKey: String) = error("unused")
    override suspend fun fetchChapterPages(sourceId: String, chapterId: String): List<Page> = error("unused")
}

/** In-memory [MangaRepository]; records upserts and answers the dedupe lookup with [existingId]. */
private class FakeMangaRepository(
    private val existingId: Uuid? = null,
    private val existing: Manga? = null
) : MangaRepository {
    val upserted = mutableListOf<Manga>()
    val coverHeaders = mutableListOf<Pair<Uuid, Map<String, String>>>()

    override suspend fun getMangaIdBySourceAndKey(sourceId: Uuid, sourceMangaKey: String): Uuid? = existingId
    override suspend fun upsertManga(manga: Manga) { upserted += manga }

    override suspend fun setSourceDetails(
        mangaId: Uuid,
        title: Text,
        author: Text?,
        description: Text?,
        publishStatus: PublishStatus,
        cover: MangaCover
    ) = error("unused")

    override suspend fun setCoverHeaders(mangaId: Uuid, headers: Map<String, String>) {
        coverHeaders += mangaId to headers
    }

    override suspend fun recordChapterSync(
        mangaId: Uuid,
        at: Instant,
        nextUpdateAt: Instant,
        fetchInterval: Int
    ) = error("unused")

    override suspend fun markDetailsFetched(mangaId: Uuid, at: Instant) = error("unused")

    override fun observeManga(mangaId: Uuid): Flow<Manga?> = flowOf(existing)

    override suspend fun getMangaByIds(mangaIds: Collection<Uuid>): List<Manga> = error("unused")
    override fun observeMangaByIds(mangaIds: Collection<Uuid>): Flow<List<Manga>> = error("unused")
    override fun observeLibrary(): Flow<List<LibraryManga>> = flowOf(emptyList())
    override suspend fun getLibraryUpdateTargets(): List<LibraryUpdateTarget> = error("unused")

    override suspend fun getMangaIdsBySource(sourceId: Uuid): List<Uuid> = emptyList()
    override suspend fun deleteManga(mangaId: Uuid) = error("unused")
    override suspend fun setFilteredScanlators(mangaId: Uuid, scanlators: Set<String>) = error("unused")

    override suspend fun setReadingStatus(mangaId: Uuid, status: ReadingStatus) = error("unused")
    override suspend fun setReadingMode(mangaId: Uuid, mode: ReadingMode) = error("unused")

    override suspend fun addToLibrary(mangaId: Uuid) = error("unused")
    override suspend fun removeFromLibrary(mangaId: Uuid) = error("unused")
}
