package app.tanuki.core.domain.chapter.usecase

import app.tanuki.core.domain.chapter.model.Chapter
import app.tanuki.core.domain.chapter.model.ChapterNumber
import app.tanuki.core.domain.chapter.model.ReadingProgress
import app.tanuki.core.domain.chapter.repository.ChapterRepository
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Tests [SyncSourceChapters] against in-memory fakes. The behaviours that matter are all about not
 * destroying local state: a re-sync must reuse existing rows (so reading progress survives), it must
 * keep chapters the user has touched even when the source drops them, and it must refuse to delete
 * anything on an empty response.
 */
class SyncSourceChaptersTest {

    @Test
    fun `writes fetched chapters as new rows`() = runTest {
        val chapters = FakeChapterRepository()
        val sync = sync(chapters, listings = listOf(listing("md-1", 1.0, "Romance Dawn"), listing("md-2", 2.0)))

        sync(MANGA.id)

        val written = chapters.upserted.single()
        assertEquals(2, written.size)
        assertEquals(MANGA.id, written.first().mangaId)
        assertEquals("md-1", written.first().sourceChapterKey?.value)
        assertEquals("Romance Dawn", written.first().name?.value)
        assertEquals(1.0, written.first().chapterNumber?.value)
        // The source published no per-chapter permalink, so every chapter falls back to the series URL
        // — which is only safe because identity now lives in the key.
        assertEquals(MANGA.url.value, written.first().url.value)
        // An untitled chapter stays untitled: domain code must not invent a display name.
        assertNull(written[1].name)
    }

    @Test
    fun `a re-sync reuses the existing row so reading progress survives`() = runTest {
        val existing = chapter(key = "md-1", number = 1.0, name = "Romance Dawn", read = true)
        val chapters = FakeChapterRepository(existing = listOf(existing))
        // Same chapter, but the source has since corrected its title.
        val sync = sync(chapters, listings = listOf(listing("md-1", 1.0, "Romance Dawn!")))

        sync(MANGA.id)

        val written = chapters.upserted.single().single()
        assertEquals(existing.id, written.id, "the local id must be reused, or the progress row is orphaned")
        assertEquals(existing.dateFetch, written.dateFetch, "dateFetch records the first fetch, not this one")
        assertEquals("Romance Dawn!", written.name?.value, "source-owned fields still refresh")
        assertTrue(chapters.deleted.isEmpty())
    }

    @Test
    fun `chapters the source dropped are removed only when untouched`() = runTest {
        val untouched = chapter(key = "md-2", number = 2.0)
        val read = chapter(key = "md-3", number = 3.0, read = true)
        val chapters = FakeChapterRepository(existing = listOf(untouched, read))
        val sync = sync(chapters, listings = listOf(listing("md-1", 1.0)))

        sync(MANGA.id)

        // md-2 is gone upstream and unread → dropped. md-3 is gone too, but the user read it, and
        // deleting it would cascade their progress away with it.
        assertContentEquals(listOf(untouched.id), chapters.deleted.single())
    }

    @Test
    fun `an empty response deletes nothing`() = runTest {
        val chapters = FakeChapterRepository(existing = listOf(chapter(key = "md-1", number = 1.0)))
        val sync = sync(chapters, listings = emptyList())

        sync(MANGA.id)

        // Far likelier a source hiccup than a series losing every chapter.
        assertTrue(chapters.deleted.isEmpty(), "an empty feed must not be read as 'everything was removed'")
    }

    @Test
    fun `a manga with no source key is a no-op`() = runTest {
        val chapters = FakeChapterRepository()
        val sync = sync(chapters, listings = listOf(listing("md-1", 1.0)), manga = MANGA.copy(sourceMangaKey = null))

        sync(MANGA.id)

        assertTrue(chapters.upserted.isEmpty(), "there is no key to ask the source about")
    }

    // --- helpers ---------------------------------------------------------------------------------

    private fun sync(
        chapters: FakeChapterRepository,
        listings: List<ChapterListing>,
        manga: Manga = MANGA
    ) = SyncSourceChapters.Default(
        mangaRepository = FakeMangaRepository(manga),
        sourceRepository = FakeSourceRepository(SOURCE),
        chapterRepository = chapters,
        sourceLoader = FakeSourceLoader(listings),
    )

    private fun listing(key: String, number: Double, name: String? = null) =
        ChapterListing(
            chapterKey = key,
            name = name,
            number = ChapterNumber.orNull(number),
            dateUpload = Instant.fromEpochMilliseconds(1_514_809_800_000),
            scanlator = "TCB Scans",
        )

    private fun chapter(key: String, number: Double, name: String? = null, read: Boolean = false) =
        Chapter(
            id = uuidV7(),
            mangaId = MANGA.id,
            chapterNumber = ChapterNumber.orNull(number),
            name = name?.let(::Text),
            sourceChapterKey = Text(key),
            url = MANGA.url,
            dateUpload = null,
            dateFetch = Instant.fromEpochMilliseconds(1_000),
            progress = if (read) ReadingProgress(hasBookmark = false, lastPageRead = 12u, alreadyRead = true) else null,
        )

    private companion object {
        val SOURCE = Source(
            id = uuidV7(),
            sourceId = "mangadex",
            lang = Text("en"),
            name = Text("MangaDex"),
            url = Url("https://mangadex.org"),
            version = 2,
        )

        val MANGA = Manga(
            id = uuidV7(),
            source = SOURCE.id,
            title = Text("One Piece"),
            author = Text("Eiichiro Oda"),
            description = null,
            cover = MangaCover(mangaId = uuidV7(), url = null, lastModified = Clock.System.now()),
            sourceMangaKey = Text("op-id"),
            url = Url("https://mangadex.org/title/op-id"),
            dateAdded = null,
            readingMode = ReadingMode.LEFT_TO_RIGHT,
            publishStatus = PublishStatus.ONGOING,
            readingStatus = null,
        )
    }
}

/** In-memory [ChapterRepository] recording each bulk write and delete as its own batch. */
private class FakeChapterRepository(private val existing: List<Chapter> = emptyList()) : ChapterRepository {
    val upserted = mutableListOf<List<Chapter>>()
    val deleted = mutableListOf<List<Uuid>>()

    override suspend fun getChapters(mangaId: Uuid): List<Chapter> = existing
    override suspend fun upsertChapters(chapters: List<Chapter>) { upserted += chapters }
    override suspend fun deleteChapters(chapterIds: List<Uuid>) { deleted += chapterIds }

    override fun observeChapters(mangaId: Uuid): Flow<List<Chapter>> = flowOf(existing)
    override fun observeBookmarkedChapters(mangaId: Uuid): Flow<List<Chapter>> = flowOf(emptyList())
    override suspend fun upsertChapter(chapter: Chapter) = error("the sync writes in bulk")
    override suspend fun deleteChapter(chapterId: Uuid) = error("the sync deletes in bulk")
}

/** A [MangaRepository] that observes one canned manga. */
private class FakeMangaRepository(private val manga: Manga?) : MangaRepository {
    override fun observeManga(mangaId: Uuid): Flow<Manga?> = flowOf(manga)

    override fun observeLibrary(): Flow<List<Manga>> = flowOf(emptyList())
    override fun observeMangaBySource(sourceId: Uuid): Flow<List<Manga>> = flowOf(emptyList())
    override suspend fun getMangaIdBySourceAndKey(sourceId: Uuid, sourceMangaKey: String): Uuid? = error("unused")
    override suspend fun upsertManga(manga: Manga) = error("unused")
    override suspend fun deleteManga(mangaId: Uuid) = error("unused")
    override suspend fun addToLibrary(mangaId: Uuid) = error("unused")
    override suspend fun removeFromLibrary(mangaId: Uuid) = error("unused")
}

/** A [SourceRepository] holding one canned source. */
private class FakeSourceRepository(private val source: Source) :
    app.tanuki.core.domain.source.repository.SourceRepository {
    override suspend fun getSource(id: Uuid): Source? = source
    override suspend fun getSourceByNativeId(sourceId: String): Source? = source
    override fun observeSources(): Flow<List<Source>> = flowOf(listOf(source))
    override suspend fun addSource(source: Source) = error("unused")
    override suspend fun deleteSource(source: Source) = error("unused")
}

/** A [SourceLoader] whose chapter feed is canned; it records what it was asked for. */
private class FakeSourceLoader(private val listings: List<ChapterListing>) : SourceLoader {
    override suspend fun fetchChapterList(sourceId: String, mangaKey: String): List<ChapterListing> = listings

    override suspend fun loadSource(id: String): Source = error("unused")
    override suspend fun listSources(): List<SourceMeta> = error("unused")
    override suspend fun fetchMangaList(sourceId: String, listing: SourceListing?, query: String?, page: Int): MangaPage =
        error("unused")
    override suspend fun fetchMangaDetails(sourceId: String, mangaKey: String): MangaListing = error("unused")
    override suspend fun fetchChapterPages(sourceId: String, chapterId: String): List<Page> = error("unused")
}