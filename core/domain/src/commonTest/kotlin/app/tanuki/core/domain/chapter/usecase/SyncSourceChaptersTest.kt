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
import app.tanuki.core.domain.manga.model.ReadingStatus
import app.tanuki.core.domain.manga.repository.MangaRepository
import app.tanuki.core.domain.source.SourceLoader
import app.tanuki.core.domain.source.model.ChapterListing
import app.tanuki.core.domain.source.model.MangaListing
import app.tanuki.core.domain.source.model.MangaPage
import app.tanuki.core.domain.source.model.Page
import app.tanuki.core.domain.source.model.Source
import app.tanuki.core.domain.source.model.SourceListing
import app.tanuki.core.domain.source.model.SourceMeta
import app.tanuki.core.domain.update.model.ChapterUpdate
import app.tanuki.core.domain.update.repository.ChapterUpdateRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import app.tanuki.core.domain.download.usecase.inertAutoDownloadNewChapters
import app.tanuki.core.domain.download.usecase.inertDeleteChapterDownloads
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
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
    fun `writes fetched chapters as new rows`() =
        runTest {
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
    fun `two groups on the same chapter number stay separate rows told apart by scanlator`() =
        runTest {
            val chapters = FakeChapterRepository()
            // What MangaDex actually returns for a popular series: one chapter number, two uploads.
            val sync =
                sync(
                    chapters,
                    listings =
                        listOf(
                            listing("md-1", 1.0, scanlator = "TCB Scans"),
                            listing("md-2", 1.0, scanlator = "Mangastream")
                        )
                )

            sync(MANGA.id)

            val written = chapters.upserted.single()
            assertEquals(2, written.size, "same-numbered chapters from different groups are not deduplicated")
            assertEquals(listOf("TCB Scans", "Mangastream"), written.map { it.scanlator?.value })
        }

    @Test
    fun `a chapter the source credits to no group has no scanlator`() =
        runTest {
            val chapters = FakeChapterRepository()
            val sync = sync(chapters, listings = listOf(listing("md-1", 1.0, scanlator = null)))

            sync(MANGA.id)

            assertNull(
                chapters.upserted
                    .single()
                    .single()
                    .scanlator
            )
        }

    @Test
    fun `a re-sync reuses the existing row so reading progress survives`() =
        runTest {
            val existing = chapter(key = "md-1", number = 1.0, name = "Romance Dawn", read = true)
            val chapters = FakeChapterRepository(existing = listOf(existing))
            // Same chapter, but the source has since corrected its title.
            val sync = sync(chapters, listings = listOf(listing("md-1", 1.0, "Romance Dawn!")))

            sync(MANGA.id)

            val written = chapters.upserted.single().single()
            assertEquals(existing.id, written.id, "the local id must be reused, or the progress row is orphaned")
            assertEquals(existing.dateFetch, written.dateFetch, "dateFetch records the first fetch, not this one")
            assertEquals("Romance Dawn!", written.name?.value, "source-owned fields still refresh")
            assertEquals("TCB Scans", written.scanlator?.value, "a re-credited group refreshes like any source field")
            assertTrue(chapters.deleted.isEmpty())
        }

    @Test
    fun `chapters the source dropped are removed only when untouched`() =
        runTest {
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
    fun `an empty response deletes nothing`() =
        runTest {
            val chapters = FakeChapterRepository(existing = listOf(chapter(key = "md-1", number = 1.0)))
            val sync = sync(chapters, listings = emptyList())

            sync(MANGA.id)

            // Far likelier a source hiccup than a series losing every chapter.
            assertTrue(chapters.deleted.isEmpty(), "an empty feed must not be read as 'everything was removed'")
        }

    @Test
    fun `a manga with no source key is a no-op`() =
        runTest {
            val chapters = FakeChapterRepository()
            val sync =
                sync(chapters, listings = listOf(listing("md-1", 1.0)), manga = MANGA.copy(sourceMangaKey = null))

            sync(MANGA.id)

            assertTrue(chapters.upserted.isEmpty(), "there is no key to ask the source about")
        }

    @Test
    fun `returns only the chapters it added`() =
        runTest {
            val existing = chapter(key = "md-1", number = 1.0)
            val chapters = FakeChapterRepository(existing = listOf(existing))
            val sync = sync(chapters, listings = listOf(listing("md-1", 1.0), listing("md-2", 2.0)))

            val added = sync(MANGA.id)

            assertEquals(listOf("md-2"), added.map { it.sourceChapterKey?.value })
        }

    @Test
    fun `a chapter added to a library series is recorded as an update`() =
        runTest {
            val existing = chapter(key = "md-1", number = 1.0)
            val chapters = FakeChapterRepository(existing = listOf(existing))
            val updates = FakeChapterUpdateRepository()
            val sync =
                sync(
                    chapters,
                    listings = listOf(listing("md-1", 1.0), listing("md-2", 2.0)),
                    manga = LIBRARY_MANGA,
                    updates = updates
                )

            val added = sync(MANGA.id)

            val recorded = updates.recorded.single().single()
            assertEquals(added.single().id, recorded.chapterId)
            assertEquals(MANGA.id, recorded.mangaId)
            assertFalse(recorded.notified, "a freshly recorded update has not been announced yet")
        }

    @Test
    fun `the first sync of a series is a baseline and records nothing`() =
        runTest {
            val chapters = FakeChapterRepository()
            val updates = FakeChapterUpdateRepository()
            val sync =
                sync(
                    chapters,
                    listings = listOf(listing("md-1", 1.0), listing("md-2", 2.0)),
                    manga = LIBRARY_MANGA,
                    updates = updates
                )

            sync(MANGA.id)

            // Otherwise adding a long-running series announces its entire back catalogue as brand new.
            assertTrue(updates.recorded.isEmpty(), "a series' existing chapters are not news")
        }

    @Test
    fun `a series the user only browsed records no updates`() =
        runTest {
            val chapters = FakeChapterRepository(existing = listOf(chapter(key = "md-1", number = 1.0)))
            val updates = FakeChapterUpdateRepository()
            val sync = sync(chapters, listings = listOf(listing("md-2", 2.0)), updates = updates)

            sync(MANGA.id)

            // MANGA has no dateAdded — it is cached, not followed.
            assertTrue(updates.recorded.isEmpty(), "a title outside the library has nothing to announce")
        }

    @Test
    fun `a re-sync that adds nothing records nothing`() =
        runTest {
            val chapters = FakeChapterRepository(existing = listOf(chapter(key = "md-1", number = 1.0)))
            val updates = FakeChapterUpdateRepository()
            val sync =
                sync(chapters, listings = listOf(listing("md-1", 1.0)), manga = LIBRARY_MANGA, updates = updates)

            sync(MANGA.id)

            assertTrue(updates.recorded.isEmpty(), "a chapter already held is not an update")
        }

    // --- staleness gate --------------------------------------------------------------------------

    @Test
    fun `a series synced moments ago is not asked again`() =
        runTest {
            val chapters = FakeChapterRepository()
            val loader = FakeSourceLoader(listOf(listing("md-1", 1.0)))
            val sync =
                sync(
                    chapters,
                    listings = emptyList(),
                    manga = MANGA.copy(chaptersFetchedAt = Clock.System.now()),
                    loader = loader
                )

            val added = sync(MANGA.id)

            assertEquals(0, loader.chapterFetches, "the source must not be asked inside the TTL")
            assertTrue(added.isEmpty())
            assertTrue(chapters.upserted.isEmpty(), "a suppressed sync writes nothing at all")
        }

    @Test
    fun `a series synced long ago is asked again`() =
        runTest {
            val chapters = FakeChapterRepository()
            val loader = FakeSourceLoader(listOf(listing("md-1", 1.0)))
            val sync =
                sync(
                    chapters,
                    listings = emptyList(),
                    // Comfortably past any plausible TTL, so this test doesn't encode the exact value.
                    manga = MANGA.copy(chaptersFetchedAt = Clock.System.now() - 30.days),
                    loader = loader
                )

            assertEquals(1, sync(MANGA.id).size)
            assertEquals(1, loader.chapterFetches)
        }

    @Test
    fun `a series that has never been synced is asked`() =
        runTest {
            val chapters = FakeChapterRepository()
            val loader = FakeSourceLoader(listOf(listing("md-1", 1.0)))
            // No stamp is what every row has right after the migration, and what a freshly cached series
            // has: it must read as "ask now", or an upgrade would look like a dead app.
            val sync = sync(chapters, listings = emptyList(), manga = MANGA, loader = loader)

            sync(MANGA.id)

            assertEquals(1, loader.chapterFetches)
        }

    @Test
    fun `force overrides a fresh stamp`() =
        runTest {
            val chapters = FakeChapterRepository()
            val loader = FakeSourceLoader(listOf(listing("md-1", 1.0)))
            val sync =
                sync(
                    chapters,
                    listings = emptyList(),
                    manga = MANGA.copy(chaptersFetchedAt = Clock.System.now()),
                    loader = loader
                )

            // What the library update run passes: it schedules series itself and must not be gated twice.
            sync(MANGA.id, force = true)

            assertEquals(1, loader.chapterFetches)
        }

    @Test
    fun `a completed sync stamps the manga`() =
        runTest {
            val repository = FakeMangaRepository(MANGA)
            val sync = sync(FakeChapterRepository(), listings = listOf(listing("md-1", 1.0)), mangaRepository = repository)

            sync(MANGA.id)

            assertEquals(MANGA.id, repository.chaptersFetched.single().first)
        }

    @Test
    fun `a completed sync schedules the next check in the same write`() =
        runTest {
            // The schedule is derived here rather than by the library run, because this is where the
            // merged chapter list already is — and it rides along on the stamp's write so the two do not
            // invalidate the library and detail queries twice over.
            val repository = FakeMangaRepository(MANGA)
            val sync = sync(FakeChapterRepository(), listings = listOf(listing("md-1", 1.0)), mangaRepository = repository)

            sync(MANGA.id)

            val (nextUpdateAt, interval) = repository.schedules.single()
            assertEquals(1, repository.chaptersFetched.size, "the stamp and the schedule are one write")
            assertEquals(7, interval, "one chapter is no cadence at all — the default applies")
            assertTrue(nextUpdateAt > repository.chaptersFetched.single().second)
        }

    @Test
    fun `a sync that fails leaves the schedule alone`() =
        runTest {
            val repository = FakeMangaRepository(MANGA)
            val sync =
                sync(
                    FakeChapterRepository(),
                    listings = emptyList(),
                    loader = FakeSourceLoader(emptyList(), failure = IllegalStateException("offline")),
                    mangaRepository = repository
                )

            assertFailsWith<IllegalStateException> { sync(MANGA.id) }

            // A source that was down says nothing about how often the series publishes; pushing its next
            // check out would turn one bad afternoon into weeks of silence.
            assertTrue(repository.schedules.isEmpty())
        }

    @Test
    fun `a suppressed sync schedules nothing`() =
        runTest {
            val repository = FakeMangaRepository(MANGA.copy(chaptersFetchedAt = Clock.System.now()))
            val sync = sync(FakeChapterRepository(), listings = listOf(listing("md-1", 1.0)), mangaRepository = repository)

            sync(MANGA.id)

            assertTrue(repository.schedules.isEmpty(), "the gate is ahead of every side effect, this one too")
        }

    @Test
    fun `a sync that fails leaves the stamp alone`() =
        runTest {
            val repository = FakeMangaRepository(MANGA)
            val sync =
                sync(
                    FakeChapterRepository(),
                    listings = emptyList(),
                    loader = FakeSourceLoader(emptyList(), failure = IllegalStateException("offline")),
                    mangaRepository = repository
                )

            assertFailsWith<IllegalStateException> { sync(MANGA.id) }

            // Otherwise a single failed refresh would suppress every retry for the whole TTL.
            assertTrue(repository.chaptersFetched.isEmpty())
        }

    // --- helpers ---------------------------------------------------------------------------------

    private fun sync(
        chapters: FakeChapterRepository,
        listings: List<ChapterListing>,
        manga: Manga = MANGA,
        updates: FakeChapterUpdateRepository = FakeChapterUpdateRepository(),
        loader: FakeSourceLoader = FakeSourceLoader(listings),
        mangaRepository: FakeMangaRepository = FakeMangaRepository(manga)
    ) = SyncSourceChapters.Default(
        mangaRepository = mangaRepository,
        sourceRepository = FakeSourceRepository(SOURCE),
        chapterRepository = chapters,
        chapterUpdateRepository = updates,
        sourceLoader = loader,
        autoDownloadNewChapters = inertAutoDownloadNewChapters(),
        deleteChapterDownloads = inertDeleteChapterDownloads()
    )

    private fun listing(
        key: String,
        number: Double,
        name: String? = null,
        scanlator: String? = "TCB Scans"
    ) = ChapterListing(
        chapterKey = key,
        name = name,
        number = ChapterNumber.orNull(number),
        dateUpload = Instant.fromEpochMilliseconds(1_514_809_800_000),
        scanlator = scanlator
    )

    private fun chapter(
        key: String,
        number: Double,
        name: String? = null,
        read: Boolean = false
    ) = Chapter(
        id = uuidV7(),
        mangaId = MANGA.id,
        chapterNumber = ChapterNumber.orNull(number),
        name = name?.let(::Text),
        scanlator = null,
        sourceChapterKey = Text(key),
        url = MANGA.url,
        dateUpload = null,
        dateFetch = Instant.fromEpochMilliseconds(1_000),
        progress = if (read) ReadingProgress(hasBookmark = false, lastPageRead = 12u, alreadyRead = true) else null
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
                author = Text("Eiichiro Oda"),
                description = null,
                cover = MangaCover(mangaId = uuidV7(), url = null, lastModified = Clock.System.now()),
                sourceMangaKey = Text("op-id"),
                url = Url("https://mangadex.org/title/op-id"),
                dateAdded = null,
                readingMode = ReadingMode.LEFT_TO_RIGHT,
                publishStatus = PublishStatus.ONGOING,
                readingStatus = null
            )

        /** The same series, followed — `dateAdded` is exactly what library membership means. */
        val LIBRARY_MANGA =
            MANGA.copy(
                dateAdded = Instant.fromEpochMilliseconds(1_500),
                readingStatus = ReadingStatus.READING
            )
    }
}

/** In-memory [ChapterUpdateRepository] recording each batch of updates as its own write. */
private class FakeChapterUpdateRepository : ChapterUpdateRepository {
    val recorded = mutableListOf<List<ChapterUpdate>>()

    override suspend fun recordUpdates(updates: List<ChapterUpdate>) {
        recorded += updates
    }

    override fun observeUpdates(): Flow<List<ChapterUpdate>> = flowOf(recorded.flatten())

    override suspend fun getUnnotified(): List<ChapterUpdate> = recorded.flatten()

    override suspend fun markNotified(chapterIds: List<Uuid>) = error("the sync never notifies")

    override suspend fun deleteForManga(mangaId: Uuid) = error("the sync never clears updates")

    override suspend fun deleteChaptersForManga(
        mangaId: Uuid,
        chapterIds: List<Uuid>
    ) {
        TODO("Not yet implemented")
    }
}

/** In-memory [ChapterRepository] recording each bulk write and delete as its own batch. */
private class FakeChapterRepository(
    private val existing: List<Chapter> = emptyList()
) : ChapterRepository {
    val upserted = mutableListOf<List<Chapter>>()
    val deleted = mutableListOf<List<Uuid>>()

    override suspend fun getChapters(mangaId: Uuid): List<Chapter> = existing

    override suspend fun upsertChapters(chapters: List<Chapter>) {
        upserted += chapters
    }

    override suspend fun deleteChapters(chapterIds: List<Uuid>) {
        deleted += chapterIds
    }

    override fun observeChapters(mangaId: Uuid): Flow<List<Chapter>> = flowOf(existing)

    override fun observeBookmarkedChapters(mangaId: Uuid): Flow<List<Chapter>> = flowOf(emptyList())

    override suspend fun getChapter(chapterId: Uuid): Chapter? = existing.firstOrNull { it.id == chapterId }

    override suspend fun getChaptersByIds(chapterIds: Collection<Uuid>): List<Chapter> =
        existing.filter { it.id in chapterIds }

    override suspend fun updateProgress(
        chapterId: Uuid,
        progress: ReadingProgress
    ) = error("the sync never writes progress")

    override suspend fun updateProgress(progress: Map<Uuid, ReadingProgress>) =
        error("the sync never writes progress")

    override suspend fun upsertChapter(chapter: Chapter) = error("the sync writes in bulk")

    override suspend fun deleteChapter(chapterId: Uuid) = error("the sync deletes in bulk")
}

/** A [MangaRepository] that observes one canned manga. */
private class FakeMangaRepository(
    private val manga: Manga?
) : MangaRepository {
    val chaptersFetched = mutableListOf<Pair<Uuid, Instant>>()

    /** The schedule each completed sync derived, as (nextUpdateAt, fetchInterval). */
    val schedules = mutableListOf<Pair<Instant, Int>>()

    override fun observeManga(mangaId: Uuid): Flow<Manga?> = flowOf(manga)

    override suspend fun getMangaByIds(mangaIds: Collection<Uuid>): List<Manga> =
        listOfNotNull(manga).filter { it.id in mangaIds }

    override fun observeLibrary(): Flow<List<Manga>> = flowOf(emptyList())

    override fun observeMangaBySource(sourceId: Uuid): Flow<List<Manga>> = flowOf(emptyList())

    override suspend fun getMangaIdBySourceAndKey(
        sourceId: Uuid,
        sourceMangaKey: String
    ): Uuid? = error("unused")

    override suspend fun upsertManga(manga: Manga) = error("unused")

    override suspend fun setSourceDetails(
        mangaId: Uuid,
        title: Text,
        author: Text?,
        description: Text?,
        publishStatus: PublishStatus,
        cover: MangaCover
    ) = error("unused")

    override suspend fun setCoverHeaders(mangaId: Uuid, headers: Map<String, String>) = error("unused")

    override suspend fun recordChapterSync(
        mangaId: Uuid,
        at: Instant,
        nextUpdateAt: Instant,
        fetchInterval: Int
    ) {
        chaptersFetched += mangaId to at
        schedules += nextUpdateAt to fetchInterval
    }

    override suspend fun markDetailsFetched(mangaId: Uuid, at: Instant) = error("unused")

    override suspend fun deleteManga(mangaId: Uuid) = error("unused")

    override suspend fun setFilteredScanlators(
        mangaId: Uuid,
        scanlators: Set<String>
    ) = error("unused")

    override suspend fun setReadingStatus(
        mangaId: Uuid,
        status: ReadingStatus
    ) = error("unused")

    override suspend fun setReadingMode(
        mangaId: Uuid,
        mode: ReadingMode
    ) = error("unused")

    override suspend fun addToLibrary(mangaId: Uuid) = error("unused")

    override suspend fun removeFromLibrary(mangaId: Uuid) = error("unused")
}

/** A [SourceRepository] holding one canned source. */
private class FakeSourceRepository(
    private val source: Source
) : app.tanuki.core.domain.source.repository.SourceRepository {
    override suspend fun getSource(id: Uuid): Source? = source

    override suspend fun getSourceByNativeId(sourceId: String): Source? = source

    override fun observeSources(): Flow<List<Source>> = flowOf(listOf(source))

    override suspend fun addSource(source: Source) = error("unused")

    override suspend fun deleteSource(source: Source) = error("unused")
}

/** A [SourceLoader] whose chapter feed is canned; it records what it was asked for. */
private class FakeSourceLoader(
    private val listings: List<ChapterListing>,
    private val failure: Throwable? = null
) : SourceLoader {
    var chapterFetches: Int = 0
        private set

    override suspend fun fetchChapterList(
        sourceId: String,
        mangaKey: String
    ): List<ChapterListing> {
        chapterFetches++
        failure?.let { throw it }
        return listings
    }

    override suspend fun loadSource(id: String): Source = error("unused")

    override fun observeInstalledSources(): Flow<List<SourceMeta>> = error("unused")

    override suspend fun fetchMangaList(
        sourceId: String,
        listing: SourceListing?,
        query: String?,
        page: Int
    ): MangaPage = error("unused")

    override suspend fun fetchMangaDetails(
        sourceId: String,
        mangaKey: String
    ): MangaListing = error("unused")

    override suspend fun fetchChapterPages(
        sourceId: String,
        chapterId: String
    ): List<Page> = error("unused")
}