package app.tanuki.core.domain.update.usecase

import app.tanuki.core.domain.chapter.model.Chapter
import app.tanuki.core.domain.chapter.model.ChapterNumber
import app.tanuki.core.domain.chapter.model.ReadingProgress
import app.tanuki.core.domain.chapter.repository.ChapterRepository
import app.tanuki.core.domain.chapter.usecase.SyncSourceChapters
import app.tanuki.core.domain.common.model.Text
import app.tanuki.core.domain.common.model.Url
import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.common.service.LogPriority
import app.tanuki.core.domain.common.service.OfflineException
import app.tanuki.core.domain.common.service.RecordingLogger
import app.tanuki.core.domain.common.service.uuidV7
import app.tanuki.core.domain.download.usecase.inertAutoDownloadNewChapters
import app.tanuki.core.domain.download.usecase.inertDeleteChapterDownloads
import app.tanuki.core.domain.library.model.LibraryManga
import app.tanuki.core.domain.library.model.LibraryPreferences
import app.tanuki.core.domain.manga.model.Manga
import app.tanuki.core.domain.manga.model.MangaCover
import app.tanuki.core.domain.manga.model.PublishStatus
import app.tanuki.core.domain.manga.model.ReadingMode
import app.tanuki.core.domain.manga.model.ReadingStatus
import app.tanuki.core.domain.manga.repository.MangaRepository
import app.tanuki.core.domain.settings.model.AutoDownloadMode
import app.tanuki.core.domain.settings.model.DeleteAfterReading
import app.tanuki.core.domain.settings.model.LibraryGridSize
import app.tanuki.core.domain.settings.model.LibraryUpdateInterval
import app.tanuki.core.domain.settings.model.ThemePreference
import app.tanuki.core.domain.settings.repository.SettingsRepository
import app.tanuki.core.domain.source.SourceLoader
import app.tanuki.core.domain.source.model.ChapterListing
import app.tanuki.core.domain.source.model.MangaListing
import app.tanuki.core.domain.source.model.MangaPage
import app.tanuki.core.domain.source.model.Page
import app.tanuki.core.domain.source.model.Source
import app.tanuki.core.domain.source.model.SourceListing
import app.tanuki.core.domain.source.model.SourceMeta
import app.tanuki.core.domain.source.repository.SourceRepository
import app.tanuki.core.domain.update.model.ChapterUpdate
import app.tanuki.core.domain.update.model.LibraryUpdateTarget
import app.tanuki.core.domain.update.model.MangaUpdateSummary
import app.tanuki.core.domain.update.repository.ChapterUpdateRepository
import app.tanuki.core.domain.update.service.LibraryUpdateNotifier
import app.tanuki.core.domain.update.usecase.LibraryUpdateResult.Completed
import app.tanuki.core.domain.update.usecase.LibraryUpdateResult.Skipped
import app.tanuki.core.domain.update.usecase.UpdateLibrary.Trigger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

/**
 * Tests [UpdateLibrary] over the real [SyncSourceChapters] on in-memory fakes — a use case is a sealed
 * interface and cannot be doubled from a test module, and running the genuine sync is the better test
 * anyway: it proves a run also *records* what it found, not just that it called something.
 *
 * The behaviours that matter are the ones around the sync: a broken series must not take the run down,
 * a run that achieved nothing must not burn the interval, and a scheduled run must respect the user's
 * setting while a manual one ignores it.
 *
 * Each series starts with one chapter already held, so a sync is a refresh rather than a baseline —
 * otherwise nothing it found would count as new (see [SyncSourceChapters]).
 */
class UpdateLibraryTest {
    @Test
    fun `refreshes every series in the library`() =
        runTest {
            val world = World(series = listOf("one-piece", "berserk"), newChaptersEach = 2)

            val result = world.update()(Trigger.Manual)

            assertEquals(Completed(checked = 2, failed = 0, newChapters = 4, updatedSeries = 2), result)
            assertEquals(setOf("one-piece", "berserk"), world.loader.asked)
        }

    @Test
    fun `a run refreshes a series the detail screen synced moments ago`() =
        runTest {
            // The gate inside SyncSourceChapters exists for the detail screen, which asks on every open.
            // A library run does its own scheduling, so it must pass through that gate, not be caught by
            // it — otherwise the very series a user is reading would be the one the run silently skips.
            val world =
                World(
                    series = listOf("one-piece", "berserk"),
                    newChaptersEach = 2,
                    chaptersFetchedAt = Clock.System.now()
                )

            val result = world.update()(Trigger.Manual)

            assertEquals(setOf("one-piece", "berserk"), world.loader.asked)
            assertEquals(Completed(checked = 2, failed = 0, newChapters = 4, updatedSeries = 2), result)
        }

    @Test
    fun `chapters found by a run are recorded as updates`() =
        runTest {
            val world = World(series = listOf("one-piece"), newChaptersEach = 3)

            world.update()(Trigger.Manual)

            assertEquals(
                3,
                world.updates.recorded
                    .flatten()
                    .size
            )
        }

    @Test
    fun `a series that fails is counted but does not stop the run`() =
        runTest {
            val world =
                World(
                    series = listOf("one-piece", "berserk", "vinland-saga"),
                    newChaptersEach = 1,
                    failing = setOf("berserk")
                )

            val result = world.update()(Trigger.Manual)

            assertEquals(Completed(checked = 3, failed = 1, newChapters = 2, updatedSeries = 2), result)
        }

    @Test
    fun `a completed run stamps the last-updated clock`() =
        runTest {
            val world = World(series = listOf("one-piece"), newChaptersEach = 1)

            world.update()(Trigger.Manual)

            assertNotNull(world.settings.lastUpdate, "the next scheduled run needs to know this one happened")
        }

    @Test
    fun `a run where every series failed does not stamp the clock`() =
        runTest {
            val world = World(series = listOf("one-piece"), failing = setOf("one-piece"))

            world.update()(Trigger.Manual)

            // Offline, or no sources installed — the next launch should retry rather than wait out a day.
            assertNull(world.settings.lastUpdate)
        }

    @Test
    fun `a run where every series failed is reported as an error`() =
        runTest {
            val world = World(series = listOf("one-piece", "berserk"), failing = setOf("one-piece", "berserk"))

            world.update()(Trigger.Manual)

            val error = world.logger.entries.single { it.priority == LogPriority.ERROR }
            assertEquals("library_update.total_failure", error.analyticsEvent)
        }

    @Test
    fun `a run that failed only because the device was offline is not reported`() =
        runTest {
            val world =
                World(series = listOf("one-piece", "berserk"), failing = setOf("one-piece", "berserk"), offline = true)

            world.update()(Trigger.Manual)

            assertTrue(world.logger.entries.none { it.priority == LogPriority.ERROR })
        }

    @Test
    fun `a run where only some series failed is not reported`() =
        runTest {
            val world = World(series = listOf("one-piece", "berserk"), failing = setOf("berserk"))

            world.update()(Trigger.Manual)

            // One source having a bad day is a warning in the trail, not a report.
            assertTrue(world.logger.entries.none { it.priority == LogPriority.ERROR })
            assertTrue(world.logger.entries.any { it.priority == LogPriority.WARN })
        }

    @Test
    fun `a scheduled run is skipped until the interval has elapsed`() =
        runTest {
            val world =
                World(
                    series = listOf("one-piece"),
                    interval = LibraryUpdateInterval.DAILY,
                    lastUpdate = Clock.System.now() - 1.hours
                )

            assertEquals(Skipped, world.update()(Trigger.Foreground))
            assertTrue(world.loader.asked.isEmpty(), "nothing should have been asked of the sources")
        }

    @Test
    fun `a scheduled run proceeds once the interval has elapsed`() =
        runTest {
            val world =
                World(
                    series = listOf("one-piece"),
                    interval = LibraryUpdateInterval.DAILY,
                    lastUpdate = Clock.System.now() - 2.days
                )

            assertTrue(world.update()(Trigger.Foreground) is Completed)
        }

    @Test
    fun `a scheduled run never fires when updates are turned off`() =
        runTest {
            val world = World(series = listOf("one-piece"), interval = LibraryUpdateInterval.OFF)

            assertEquals(Skipped, world.update()(Trigger.Foreground))
        }

    @Test
    fun `a manual run ignores the interval entirely`() =
        runTest {
            val world =
                World(
                    series = listOf("one-piece"),
                    interval = LibraryUpdateInterval.OFF,
                    lastUpdate = Clock.System.now()
                )

            // The user asked and is watching; an interval is a policy for runs they can't see.
            assertTrue(world.update()(Trigger.Manual) is Completed)
        }

    @Test
    fun `an empty library completes without asking any source`() =
        runTest {
            val world = World(series = emptyList())

            val result = world.update()(Trigger.Manual)

            assertEquals(Completed(checked = 0, failed = 0, newChapters = 0, updatedSeries = 0), result)
            assertTrue(world.loader.asked.isEmpty())
        }

    @Test
    fun `a background run announces what it found`() =
        runTest {
            val world = World(series = listOf("one-piece", "berserk"), newChaptersEach = 2)

            world.update()(Trigger.Background)

            val summaries = world.announced.single()
            assertEquals(setOf("one-piece", "berserk"), summaries.mapTo(mutableSetOf()) { it.title })
            assertTrue(summaries.all { it.newChapterCount == 2 })
        }

    @Test
    fun `runs the user can see stay silent`() =
        runTest {
            // Both find chapters; neither speaks, because the Home list they feed is already on screen.
            World(series = listOf("one-piece"), newChaptersEach = 2).let { world ->
                world.update()(Trigger.Manual)
                assertTrue(world.announced.isEmpty(), "a manual run has an audience already looking")
            }
            World(series = listOf("one-piece"), newChaptersEach = 2).let { world ->
                world.update()(Trigger.Foreground)
                assertTrue(world.announced.isEmpty(), "the launch catch-up runs while the app is open")
            }
        }

    // --- per-series scheduling ---

    @Test
    fun `a scheduled run leaves a series that is not due yet alone`() =
        runTest {
            // The whole point of the schedule: a series that cannot plausibly have published again is
            // not worth a source round-trip, however due the *library* is.
            val world =
                World(
                    series = listOf("one-piece", "berserk"),
                    nextUpdateAt = Clock.System.now() + 5.days
                )

            val result = world.update()(Trigger.Foreground)

            assertTrue(world.loader.asked.isEmpty(), "nothing should have been asked of the sources")
            assertEquals(Completed(checked = 0, failed = 0, newChapters = 0, updatedSeries = 0), result)
        }

    @Test
    fun `a run that skipped everything still stamps the last-updated clock`() =
        runTest {
            // It did everything there was to do. Not stamping would make the next launch try again
            // immediately and skip everything again, forever.
            val world = World(series = listOf("one-piece"), nextUpdateAt = Clock.System.now() + 5.days)

            world.update()(Trigger.Foreground)

            assertNotNull(world.settings.lastUpdate)
        }

    @Test
    fun `a manual run ignores the schedule too`() =
        runTest {
            val world =
                World(
                    series = listOf("one-piece", "berserk"),
                    newChaptersEach = 1,
                    nextUpdateAt = Clock.System.now() + 5.days
                )

            val result = world.update()(Trigger.Manual)

            assertEquals(setOf("one-piece", "berserk"), world.loader.asked)
            assertEquals(Completed(checked = 2, failed = 0, newChapters = 2, updatedSeries = 2), result)
        }

    @Test
    fun `a series that is due today is refreshed today`() =
        runTest {
            // Due at midnight this morning, run at whatever time it is now: the grace window is what
            // stops a check falling due today from being pushed to tomorrow.
            val world = World(series = listOf("one-piece"), nextUpdateAt = Clock.System.now() - 1.hours)

            world.update()(Trigger.Foreground)

            assertEquals(setOf("one-piece"), world.loader.asked)
        }

    @Test
    fun `a series that has never been scheduled is always refreshed`() =
        runTest {
            // What makes the first run after this feature ships behave exactly as before.
            val world = World(series = listOf("one-piece"), newChaptersEach = 1)

            world.update()(Trigger.Foreground)

            assertEquals(setOf("one-piece"), world.loader.asked)
        }

    @Test
    fun `a refreshed series is given a schedule`() =
        runTest {
            val world = World(series = listOf("one-piece"), newChaptersEach = 1)

            world.update()(Trigger.Manual)

            val (mangaId, nextUpdateAt, interval) = world.mangaRepository.schedules.single()
            assertEquals(world.library.single().id, mangaId)
            assertEquals(7, interval, "one held chapter is no cadence at all — the default applies")
            assertTrue(nextUpdateAt > Clock.System.now(), "a series just checked is not due again yet")
        }

    @Test
    fun `a series that could not be refreshed keeps its old schedule`() =
        runTest {
            // A source that was down says nothing about how often the series publishes, and pushing its
            // next check out would turn one bad afternoon into weeks of silence.
            val world = World(series = listOf("one-piece"), failing = setOf("one-piece"))

            world.update()(Trigger.Manual)

            assertTrue(world.mangaRepository.schedules.isEmpty())
        }

    @Test
    fun `a user-pinned interval survives a run`() =
        runTest {
            val world = World(series = listOf("one-piece"), newChaptersEach = 1, fetchInterval = -3)

            world.update()(Trigger.Manual)

            assertEquals(-3, world.mangaRepository.schedules.single().third)
        }

    @Test
    fun `a second background run does not announce the same chapters again`() =
        runTest {
            val world = World(series = listOf("one-piece"), newChaptersEach = 2)

            world.update()(Trigger.Background)
            // Nothing new the second time round; the first run's chapters are already marked notified.
            world.update()(Trigger.Background)

            assertEquals(1, world.announced.size, "an announced chapter must never be announced twice")
        }
}

/**
 * One library's worth of fakes, wired together: [series] are held with one chapter each, and their
 * source offers that chapter plus [newChaptersEach] more. A key in [failing] is a source that errors
 * when asked for that series.
 */
private class World(
    series: List<String>,
    newChaptersEach: Int = 1,
    failing: Set<String> = emptySet(),
    /** Whether [failing] series fail because the device is offline rather than the source being down. */
    offline: Boolean = false,
    private val interval: LibraryUpdateInterval = LibraryUpdateInterval.DAILY,
    lastUpdate: Instant? = null,
    /** When each series was last synced — the stamp a per-series staleness gate would read. */
    chaptersFetchedAt: Instant? = null,
    /** When each series is next due; `null` (the default) means every run considers it. */
    nextUpdateAt: Instant? = null,
    /** A pre-existing cadence. Negative stands for one the user pinned by hand. */
    fetchInterval: Int? = null
) {
    val library =
        series.map(::manga).map {
            it.copy(
                chaptersFetchedAt = chaptersFetchedAt,
                nextUpdateAt = nextUpdateAt,
                fetchInterval = fetchInterval
            )
        }
    val chapters = HeldChapterRepository(library.associate { it.id to listOf(heldChapter(it)) })

    /** Shared across the whole graph, so the schedules a run stamps are all recorded in one place. */
    val mangaRepository = FakeLibraryRepository(library)
    val updates = FakeChapterUpdateRepository()
    val settings = FakeSettingsRepository(interval, lastUpdate)
    private val notifier = RecordingNotifier()
    val logger = RecordingLogger()

    /** What each notification announced; empty when a run stayed silent. */
    val announced get() = notifier.announced
    val loader =
        FakeSourceLoader(
            listings =
                series.associateWith { key ->
                    // "md-0" is the chapter already held, so only the rest count as new.
                    List(newChaptersEach + 1) { index -> listing("md-$index", index.toDouble()) }
                },
            failing = failing,
            offline = offline
        )

    fun update(): UpdateLibrary =
        UpdateLibrary.Default(
            mangaRepository = mangaRepository,
            syncSourceChapters =
                SyncSourceChapters.Default(
                    mangaRepository = mangaRepository,
                    sourceRepository = FakeSourceRepository(SOURCE),
                    chapterRepository = chapters,
                    chapterUpdateRepository = updates,
                    sourceLoader = loader,
                    autoDownloadNewChapters = inertAutoDownloadNewChapters(),
                    deleteChapterDownloads = inertDeleteChapterDownloads()
                ),
            notifyNewChapters =
                NotifyNewChapters.Default(
                    chapterUpdateRepository = updates,
                    resolveChapterUpdates = ResolveChapterUpdates(mangaRepository, chapters),
                    notifier = notifier
                ),
            settingsRepository = settings,
            dispatchers = UnconfinedDispatchers,
            logger = logger
        )

    private fun manga(key: String) =
        Manga(
            id = uuidV7(),
            source = SOURCE.id,
            title = Text(key),
            author = null,
            description = null,
            cover = MangaCover(mangaId = uuidV7(), url = null),
            sourceMangaKey = Text(key),
            url = Url("https://mangadex.org/title/$key"),
            dateAdded = Instant.fromEpochMilliseconds(1_000),
            readingMode = ReadingMode.LEFT_TO_RIGHT,
            publishStatus = PublishStatus.ONGOING,
            readingStatus = ReadingStatus.READING
        )

    private fun heldChapter(manga: Manga) =
        Chapter(
            id = uuidV7(),
            mangaId = manga.id,
            chapterNumber = ChapterNumber.orNull(0.0),
            name = null,
            scanlator = null,
            sourceChapterKey = Text("md-0"),
            url = manga.url,
            dateUpload = null,
            dateFetch = Instant.fromEpochMilliseconds(1_000),
            progress = null
        )

    private fun listing(
        key: String,
        number: Double
    ) = ChapterListing(
        chapterKey = key,
        name = null,
        number = ChapterNumber.orNull(number),
        dateUpload = null,
        scanlator = null
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
    }
}

/** Runs every hop inline, so a run completes within `runTest` without advancing a scheduler. */
private object UnconfinedDispatchers : DispatcherProvider {
    override val default: CoroutineDispatcher = Dispatchers.Unconfined
    override val io: CoroutineDispatcher = Dispatchers.Unconfined
    override val main: CoroutineDispatcher = Dispatchers.Unconfined
}

/** A [SourceLoader] serving canned chapter feeds, recording what it was asked for. */
private class FakeSourceLoader(
    private val listings: Map<String, List<ChapterListing>>,
    private val failing: Set<String>,
    private val offline: Boolean
) : SourceLoader {
    val asked = mutableSetOf<String>()

    override suspend fun fetchChapterList(
        sourceId: String,
        mangaKey: String
    ): List<ChapterListing> {
        asked += mangaKey
        if (mangaKey in failing) {
            if (offline) throw OfflineException()
            error("source is down")
        }
        return listings[mangaKey].orEmpty()
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

/**
 * Chapters held per manga, applying writes — the notification path resolves the chapters a run just
 * added, so this has to read back what the sync wrote or there would be nothing to announce.
 */
private class HeldChapterRepository(
    held: Map<Uuid, List<Chapter>>
) : ChapterRepository {
    private val byManga = held.mapValuesTo(mutableMapOf()) { (_, chapters) -> chapters.toMutableList() }

    override suspend fun getChapters(mangaId: Uuid): List<Chapter> = byManga[mangaId].orEmpty()

    override suspend fun upsertChapters(chapters: List<Chapter>) {
        chapters.forEach { chapter ->
            val held = byManga.getOrPut(chapter.mangaId) { mutableListOf() }
            val existing = held.indexOfFirst { it.id == chapter.id }
            if (existing >= 0) held[existing] = chapter else held += chapter
        }
    }

    override suspend fun deleteChapters(chapterIds: List<Uuid>) {
        byManga.values.forEach { held -> held.removeAll { it.id in chapterIds } }
    }

    override fun observeChapters(mangaId: Uuid): Flow<List<Chapter>> = flowOf(byManga[mangaId].orEmpty())

    override fun observeBookmarkedChapters(mangaId: Uuid): Flow<List<Chapter>> = flowOf(emptyList())

    override suspend fun getChapter(chapterId: Uuid): Chapter? = null

    override suspend fun getChaptersByIds(chapterIds: Collection<Uuid>): List<Chapter> =
        byManga.values.flatten().filter { it.id in chapterIds }

    override fun observeChaptersByIds(chapterIds: Collection<Uuid>): Flow<List<Chapter>> =
        flowOf(byManga.values.flatten().filter { it.id in chapterIds })

    override suspend fun updateProgress(
        chapterId: Uuid,
        progress: ReadingProgress
    ) = error("unused")

    override suspend fun updateProgress(progress: Map<Uuid, ReadingProgress>) = error("unused")

    override suspend fun upsertChapter(chapter: Chapter) = error("unused")

    override suspend fun deleteChapter(chapterId: Uuid) = error("unused")
}

/** In-memory [ChapterUpdateRepository] that tracks the notified flag, as the real one does. */
private class FakeChapterUpdateRepository : ChapterUpdateRepository {
    val recorded = mutableListOf<List<ChapterUpdate>>()
    private val notified = mutableSetOf<Uuid>()

    override suspend fun recordUpdates(updates: List<ChapterUpdate>) {
        recorded += updates
    }

    override suspend fun getUnnotified(): List<ChapterUpdate> =
        recorded.flatten().filterNot { it.chapterId in notified }

    override suspend fun markNotified(chapterIds: List<Uuid>) {
        notified += chapterIds
    }

    override fun observeUpdates(): Flow<List<ChapterUpdate>> = flowOf(recorded.flatten())

    override suspend fun deleteForManga(mangaId: Uuid) = error("unused")

    override suspend fun deleteChaptersForManga(
        mangaId: Uuid,
        chapterIds: List<Uuid>
    ) {
        TODO("Not yet implemented")
    }
}

/** Records what was announced, so a test can assert whether a run spoke and what it said. */
private class RecordingNotifier : LibraryUpdateNotifier {
    val announced = mutableListOf<List<MangaUpdateSummary>>()

    override suspend fun notifyNewChapters(summaries: List<MangaUpdateSummary>) {
        announced += summaries
    }
}

/** A [MangaRepository] whose library is canned, recording the schedules a run stamps. */
private class FakeLibraryRepository(
    private val library: List<Manga>
) : MangaRepository {
    /** Every [recordChapterSync] schedule, in order. */
    val schedules = mutableListOf<Triple<Uuid, Instant, Int>>()

    // A run reads the library through [getLibraryUpdateTargets], not this. The world still holds whole
    // manga because the chapter sync underneath it does need them.
    override fun observeLibraryKeys(sourceId: Uuid): Flow<Set<String>> = flowOf(emptySet())

    override fun observeLibrary(): Flow<List<LibraryManga>> = error("unused")

    override suspend fun getLibraryUpdateTargets(): List<LibraryUpdateTarget> =
        library.map { LibraryUpdateTarget(it.id, it.source, it.title, it.nextUpdateAt) }

    override fun observeManga(mangaId: Uuid): Flow<Manga?> = flowOf(library.firstOrNull { it.id == mangaId })

    override suspend fun getMangaByIds(mangaIds: Collection<Uuid>): List<Manga> =
        library.filter { it.id in mangaIds }

    override fun observeMangaByIds(mangaIds: Collection<Uuid>): Flow<List<Manga>> =
        flowOf(library.filter { it.id in mangaIds })

    override suspend fun getMangaIdsBySource(sourceId: Uuid): List<Uuid> = emptyList()

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

    // The real SyncSourceChapters calls this after every successful refresh. The `at` stamp is accepted
    // and dropped — what this suite asserts about the staleness gate is behavioural, and visible in the
    // loader's fetch count rather than here — but the schedule is recorded, since whether a run hands
    // out one at all is exactly what these tests are about.
    override suspend fun recordChapterSync(
        mangaId: Uuid,
        at: Instant,
        nextUpdateAt: Instant,
        fetchInterval: Int
    ) {
        schedules += Triple(mangaId, nextUpdateAt, fetchInterval)
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

    override suspend fun suggestReadingMode(mangaId: Uuid, mode: ReadingMode) = error("unused")

    override suspend fun setCoverOverride(mangaId: Uuid, url: String?) = error("unused")

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
) : SourceRepository {
    override suspend fun getSource(id: Uuid): Source? = source

    override suspend fun getSourceByNativeId(sourceId: String): Source? = source

    override fun observeSources(): Flow<List<Source>> = flowOf(listOf(source))

    override suspend fun addSource(source: Source) = error("unused")

    override suspend fun deleteSource(source: Source) = error("unused")
}

/** In-memory settings holding just the two an update run reads. */
private class FakeSettingsRepository(
    private val interval: LibraryUpdateInterval,
    var lastUpdate: Instant?
) : SettingsRepository {
    override fun observeLibraryUpdateInterval(): Flow<LibraryUpdateInterval> = flowOf(interval)

    override suspend fun getLastLibraryUpdate(): Instant? = lastUpdate

    override suspend fun setLastLibraryUpdate(at: Instant) {
        lastUpdate = at
    }

    override suspend fun setLibraryUpdateInterval(interval: LibraryUpdateInterval) = error("unused")

    override fun observeThemePreference(): Flow<ThemePreference> = error("unused")

    override suspend fun setThemePreference(preference: ThemePreference) = error("unused")

    override fun observeCrashReportingEnabled(): Flow<Boolean> = error("unused")

    override suspend fun setCrashReportingEnabled(enabled: Boolean) = error("unused")

    override suspend fun getOrCreateInstallId(): String = error("unused")

    override suspend fun resetInstallId(): String = error("unused")

    override suspend fun getRemovedSourceIds(): Set<String> = error("unused")

    override suspend fun setSourceRemoved(
        sourceId: String,
        removed: Boolean
    ) = error("unused")

    override fun observeDownloadsPaused(): Flow<Boolean> = error("unused")

    override suspend fun setDownloadsPaused(paused: Boolean) = error("unused")

    override fun observeAutoDownloadMode(): Flow<AutoDownloadMode> = error("unused")

    override fun observeAutoDownloadCategories(): Flow<Set<Uuid>> = error("unused")

    override suspend fun setAutoDownload(
        mode: AutoDownloadMode,
        categories: Set<Uuid>
    ) = error("unused")

    override fun observeDownloadOnWifiOnly(): Flow<Boolean> = error("unused")

    override suspend fun setDownloadOnWifiOnly(wifiOnly: Boolean) = error("unused")

    override fun observeDeleteAfterReading(): Flow<DeleteAfterReading> = error("unused")


    override fun observeLibraryPreferences(): Flow<LibraryPreferences> = error("unused")



    override fun observeLibraryGridSize(): Flow<LibraryGridSize> = error("unused")




    override fun observeShowSensitiveContent(): Flow<Boolean> = error("unused")




    override suspend fun setShowSensitiveContent(show: Boolean) = error("unused")



    override suspend fun setLibraryGridSize(size: LibraryGridSize) = error("unused")


    override suspend fun updateLibraryPreferences(

        transform: (LibraryPreferences) -> LibraryPreferences

    ) = error("unused")

    override suspend fun setDeleteAfterReading(setting: DeleteAfterReading) = error("unused")
}