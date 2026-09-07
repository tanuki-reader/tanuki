package app.tanuki.core.domain.download.usecase

import app.tanuki.core.domain.category.model.Category
import app.tanuki.core.domain.category.repository.CategoryRepository
import app.tanuki.core.domain.chapter.model.Chapter
import app.tanuki.core.domain.chapter.model.ChapterNumber
import app.tanuki.core.domain.chapter.model.ReadingProgress
import app.tanuki.core.domain.chapter.repository.ChapterRepository
import app.tanuki.core.domain.chapter.usecase.GetChapterPages
import app.tanuki.core.domain.common.model.Text
import app.tanuki.core.domain.common.model.Url
import app.tanuki.core.domain.common.service.ConnectionObserver
import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.common.service.RecordingLogger
import app.tanuki.core.domain.common.service.uuidV7
import app.tanuki.core.domain.download.model.ChapterDownload
import app.tanuki.core.domain.download.model.DownloadError
import app.tanuki.core.domain.download.model.DownloadManifest
import app.tanuki.core.domain.download.model.DownloadProgress
import app.tanuki.core.domain.download.model.DownloadState
import app.tanuki.core.domain.download.model.DownloadedPage
import app.tanuki.core.domain.download.repository.DownloadRepository
import app.tanuki.core.domain.download.service.ChapterDownloadPart
import app.tanuki.core.domain.download.service.ChapterDownloadStore
import app.tanuki.core.domain.download.service.DownloadNotifier
import app.tanuki.core.domain.download.service.DownloadScheduler
import app.tanuki.core.domain.download.service.FetchedPage
import app.tanuki.core.domain.download.service.PageFetchException
import app.tanuki.core.domain.download.service.PageFetcher
import app.tanuki.core.domain.download.service.StoredChapter
import app.tanuki.core.domain.manga.model.Manga
import app.tanuki.core.domain.manga.model.MangaCover
import app.tanuki.core.domain.manga.model.PublishStatus
import app.tanuki.core.domain.manga.model.ReadingMode
import app.tanuki.core.domain.manga.model.ReadingStatus
import app.tanuki.core.domain.manga.repository.MangaRepository
import app.tanuki.core.domain.settings.model.AutoDownloadMode
import app.tanuki.core.domain.settings.model.DeleteAfterReading
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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * One series' worth of downloadable chapters, wired to in-memory doubles — the fixture both the engine
 * and the use-case tests are written against.
 *
 * The page *resolution* path is the real [GetChapterPages] over a fake [SourceLoader] rather than a
 * double: a use case is a sealed interface and cannot be implemented from a test compilation, and
 * running the genuine one is the better test anyway — it proves the engine resolves a chapter the same
 * way the reader does, which is the property that makes "the page list is re-fetched per chapter" mean
 * anything.
 *
 * @param chapterCount How many chapters the series has, numbered from `1`.
 * @param pagesPerChapter How many pages each chapter's source serves.
 */
internal class DownloadWorld(
    dispatchers: DispatcherProvider,
    chapterCount: Int = 3,
    val pagesPerChapter: Int = 2,
) {
    val manga: Manga = manga()
    val chapters: List<Chapter> = List(chapterCount) { index -> chapter(index + 1) }

    val downloads = FakeDownloadRepository()
    val store = FakeChapterDownloadStore()
    val fetcher = FakePageFetcher()
    val scheduler = FakeDownloadScheduler()
    val settings = FakeSettingsRepository()
    val connection = FakeConnectionObserver()

    /** The one rule both the engine and the queue screen read. */
    val hold: ObserveDownloadHold = ObserveDownloadHold.Default(settings, connection)
    val loader = FakeSourceLoader(pagesPerChapter)
    val chapterRepository = FakeChapterRepository(chapters)
    val mangaRepository = FakeMangaRepository(manga)
    val sources = FakeSourceRepository(SOURCE)

    val manager: DownloadManager =
        DownloadManager.Default(
            downloadRepository = downloads,
            store = store,
            pageFetcher = fetcher,
            getChapterPages =
                GetChapterPages.Default(
                    chapterRepository = chapterRepository,
                    mangaRepository = mangaRepository,
                    sourceRepository = sources,
                    sourceLoader = loader,
                ),
            observeDownloadHold = hold,
            dispatchers = dispatchers,
            logger = RecordingLogger(),
        )

    val enqueue: EnqueueChapterDownloads =
        EnqueueChapterDownloads.Default(downloads, chapterRepository, scheduler)

    val enqueueNext: EnqueueNextChapters =
        EnqueueNextChapters.Default(mangaRepository, chapterRepository, enqueue)

    val cancel: CancelChapterDownloads = CancelChapterDownloads.Default(downloads, store, manager)

    val deleteChapters: DeleteChapterDownloads = DeleteChapterDownloads.Default(downloads, store, manager)

    val deleteManga: DeleteMangaDownloads = DeleteMangaDownloads.Default(downloads, store, manager)

    val deleteAll: DeleteAllDownloads = DeleteAllDownloads.Default(downloads, store, manager)

    val cancelAll: CancelAllDownloads = CancelAllDownloads.Default(downloads, store, manager)

    val setPaused: SetDownloadsPaused = SetDownloadsPaused.Default(settings, scheduler)

    val queueView: ObserveDownloadQueue =
        ObserveDownloadQueue.Default(downloads, mangaRepository, chapterRepository, dispatchers)

    val storageUsage: ObserveDownloadStorageUsage =
        ObserveDownloadStorageUsage.Default(downloads, mangaRepository, store, dispatchers)

    val notifier = FakeDownloadNotifier()

    val runQueue: RunDownloadQueue =
        RunDownloadQueue.Default(manager, queueView, notifier, RecordingLogger())

    val downloadSettings: ObserveDownloadSettings = ObserveDownloadSettings.Default(settings)

    val categories = FakeCategoryRepository()

    val autoDownload: AutoDownloadNewChapters =
        AutoDownloadNewChapters.Default(downloadSettings, categories, enqueue, RecordingLogger())

    val deleteReadDownloads: DeleteReadChapterDownloads =
        DeleteReadChapterDownloads.Default(settings, chapterRepository, downloads, deleteChapters, RecordingLogger())

    val verify: VerifyDownloads = VerifyDownloads.Default(downloads, store, RecordingLogger())

    val sourceUsage: GetSourceDownloadUsage =
        GetSourceDownloadUsage.Default(sources, mangaRepository, downloads, store)

    val deleteSourceDownloads: DeleteSourceDownloads =
        DeleteSourceDownloads.Default(sources, mangaRepository, deleteManga, RecordingLogger())

    /** Puts [chapters] straight into the queue, bypassing the enqueue use case's own rules. */
    suspend fun queue(vararg chapters: Chapter) {
        chapters.forEachIndexed { position, chapter ->
            downloads.enqueue(
                listOf(
                    ChapterDownload(
                        chapterId = chapter.id,
                        mangaId = manga.id,
                        state = DownloadState.Queued,
                        // Distinct stamps, in the order given, so "oldest first" is testable.
                        queuedAt = Instant.fromEpochMilliseconds(1_000L + position),
                    ),
                ),
            )
        }
    }

    /** Marks [chapter] read, the way the reader would. */
    fun markRead(
        chapter: Chapter,
        bookmarked: Boolean = false,
    ) {
        chapterRepository.replace(
            chapter.copy(
                progress = ReadingProgress(hasBookmark = bookmarked, lastPageRead = 0u, alreadyRead = true),
            ),
        )
    }

    /** Downloads [chapter] for real and then marks it read — where the delete-after-reading rule starts. */
    suspend fun downloadAndRead(
        chapter: Chapter,
        bookmarked: Boolean = false,
    ) {
        queue(chapter)
        manager.run()
        markRead(chapter, bookmarked)
    }

    /**
     * A downloaded chapter of a series belonging to a **different** source, for the tests that have to
     * prove a source-scoped action stops at its own source.
     *
     * Written straight into the store and the records rather than run through the engine: the fixture's
     * one [SourceLoader] only serves the one source, and what these tests need from this series is that
     * it exists on disk and is claimed by a record — not that it was fetched.
     */
    suspend fun otherSourceChapter(): StoredChapter {
        val mangaId = uuidV7()
        val chapterId = uuidV7()
        mangaRepository.add(manga.copy(id = mangaId, source = uuidV7(), title = Text("Vagabond")))
        val part = store.openPart(mangaId, chapterId)
        repeat(pagesPerChapter) { index -> part.writePage(index, "jpg", ByteArray(8)) }
        store.commit(part, pagesPerChapter)
        downloads.enqueue(
            listOf(
                ChapterDownload(
                    chapterId = chapterId,
                    mangaId = mangaId,
                    state = DownloadState.Downloaded,
                    queuedAt = Instant.fromEpochMilliseconds(500),
                ),
            ),
        )
        return StoredChapter(mangaId = mangaId, chapterId = chapterId, isComplete = true)
    }

    /** Hides [scanlator]'s chapters, the way the manga screen's filter would. */
    fun hideScanlator(scanlator: String) {
        mangaRepository.replace(manga.copy(filteredScanlators = setOf(scanlator)))
    }

    /** Credits [chapter] to [scanlator]. */
    fun creditTo(
        chapter: Chapter,
        scanlator: String,
    ) {
        chapterRepository.replace(chapter.copy(scanlator = Text(scanlator)))
    }

    /** The record for a chapter, which every assertion about the queue goes through. */
    suspend fun record(chapter: Chapter): ChapterDownload? = downloads.get(chapter.id)

    private fun manga() =
        Manga(
            id = uuidV7(),
            source = SOURCE.id,
            title = Text("Berserk"),
            author = null,
            description = null,
            cover = MangaCover(mangaId = uuidV7(), url = null, lastModified = Clock.System.now()),
            sourceMangaKey = Text("berserk"),
            url = Url("https://mangadex.org/title/berserk"),
            dateAdded = Instant.fromEpochMilliseconds(1_000),
            readingMode = ReadingMode.LEFT_TO_RIGHT,
            publishStatus = PublishStatus.ONGOING,
            readingStatus = ReadingStatus.READING,
        )

    private fun chapter(number: Int) =
        Chapter(
            id = uuidV7(),
            mangaId = manga.id,
            chapterNumber = ChapterNumber.orNull(number.toDouble()),
            name = null,
            scanlator = null,
            sourceChapterKey = Text("md-$number"),
            url = manga.url,
            dateUpload = null,
            dateFetch = Instant.fromEpochMilliseconds(1_000),
            progress = null,
        )

    private companion object {
        val SOURCE =
            Source(
                id = uuidV7(),
                sourceId = "mangadex",
                lang = Text("en"),
                name = Text("MangaDex"),
                url = Url("https://mangadex.org"),
                version = 2,
            )
    }
}

/** Runs every hop on the test scheduler, so the engine's retry backoff is skipped rather than waited out. */
internal class TestDispatchers(
    dispatcher: CoroutineDispatcher,
) : DispatcherProvider {
    override val default: CoroutineDispatcher = dispatcher
    override val io: CoroutineDispatcher = dispatcher
    override val main: CoroutineDispatcher = dispatcher
}

/**
 * In-memory [DownloadRepository] with the write semantics the real one is specified to have: [enqueue]
 * leaves existing records alone, and every other write ignores a chapter that isn't there.
 */
internal class FakeDownloadRepository : DownloadRepository {
    private val records = MutableStateFlow<Map<Uuid, ChapterDownload>>(emptyMap())

    /** Every [setProgress] write, in order — how "progress is written per page" is checked. */
    val progressWrites = mutableListOf<Pair<Int, Long>>()

    override fun observeQueue(): Flow<List<ChapterDownload>> =
        records.map { held ->
            held.values
                .filter { it.state != DownloadState.Downloaded }
                .sortedBy { it.queuedAt }
        }

    override fun observeForManga(mangaId: Uuid): Flow<Map<Uuid, ChapterDownload>> =
        records.map { held -> held.filterValues { it.mangaId == mangaId } }

    override fun observeDownloadedCounts(): Flow<Map<Uuid, Int>> =
        records.map { held ->
            held.values
                .filter(ChapterDownload::isComplete)
                .groupingBy(ChapterDownload::mangaId)
                .eachCount()
        }

    override suspend fun get(chapterId: Uuid): ChapterDownload? = records.value[chapterId]

    override suspend fun nextQueued(): ChapterDownload? =
        records.value.values
            .filter { it.state == DownloadState.Queued }
            .minByOrNull { it.queuedAt }

    override suspend fun enqueue(downloads: List<ChapterDownload>) {
        edit { held -> downloads.fold(held) { acc, download -> acc.putIfAbsent(download) } }
    }

    override suspend fun update(download: ChapterDownload) {
        edit { held -> held.replaceExisting(download.chapterId) { download } }
    }

    override suspend fun setProgress(
        chapterId: Uuid,
        downloadedPages: Int,
        bytes: Long,
    ) {
        progressWrites += downloadedPages to bytes
        edit { held ->
            held.replaceExisting(chapterId) { it.copy(downloadedPages = downloadedPages, bytes = bytes) }
        }
    }

    override suspend fun markDownloaded(
        chapterId: Uuid,
        pageCount: Int,
        bytes: Long,
        completedAt: Instant,
    ) {
        edit { held ->
            held.replaceExisting(chapterId) {
                it.copy(
                    state = DownloadState.Downloaded,
                    downloadedPages = pageCount,
                    pageCount = pageCount,
                    bytes = bytes,
                    error = null,
                    completedAt = completedAt,
                )
            }
        }
    }

    override suspend fun markFailed(
        chapterId: Uuid,
        error: DownloadError,
    ) {
        edit { held -> held.replaceExisting(chapterId) { it.copy(state = DownloadState.Failed, error = error) } }
    }

    override suspend fun requeueInterrupted(): Int {
        val interrupted = records.value.values.filter { it.state == DownloadState.Downloading }
        edit { held ->
            interrupted.fold(held) { acc, record ->
                acc.replaceExisting(record.chapterId) { it.copy(state = DownloadState.Queued) }
            }
        }
        return interrupted.size
    }

    override suspend fun delete(chapterIds: List<Uuid>) {
        edit { held -> held - chapterIds.toSet() }
    }

    override suspend fun deleteForManga(mangaId: Uuid) {
        edit { held -> held.filterValues { it.mangaId != mangaId } }
    }

    override suspend fun deleteAll() {
        edit { emptyMap() }
    }

    override suspend fun getAll(): List<ChapterDownload> = records.value.values.toList()

    private fun edit(block: (Map<Uuid, ChapterDownload>) -> Map<Uuid, ChapterDownload>) {
        records.value = block(records.value)
    }

    private fun Map<Uuid, ChapterDownload>.putIfAbsent(download: ChapterDownload) =
        if (containsKey(download.chapterId)) this else this + (download.chapterId to download)

    private fun Map<Uuid, ChapterDownload>.replaceExisting(
        chapterId: Uuid,
        block: (ChapterDownload) -> ChapterDownload,
    ) = this[chapterId]?.let { this + (chapterId to block(it)) } ?: this
}

/**
 * In-memory [ChapterDownloadStore] mirroring the real one's contract: pages accumulate in a part, a
 * chapter is downloaded only once committed, and a commit refuses anything short or gappy.
 */
internal class FakeChapterDownloadStore : ChapterDownloadStore {
    private val committed = mutableMapOf<Pair<Uuid, Uuid>, DownloadManifest>()
    private val parts = mutableMapOf<Pair<Uuid, Uuid>, MutableMap<Int, DownloadedPage>>()

    /** Mirrors the real store's signal, so anything measuring the files is woken the same way. */
    private val changes =
        MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    override fun observeChanges(): Flow<Unit> = changes

    private fun noteChange() {
        changes.tryEmit(Unit)
    }

    override suspend fun manifest(
        mangaId: Uuid,
        chapterId: Uuid,
    ): DownloadManifest? = committed[mangaId to chapterId]

    override suspend fun pagePaths(
        mangaId: Uuid,
        chapterId: Uuid,
    ): List<String>? = manifest(mangaId, chapterId)?.pages?.map { "/$mangaId/$chapterId/${it.file}" }

    override suspend fun openPart(
        mangaId: Uuid,
        chapterId: Uuid,
    ): ChapterDownloadPart {
        val pages = parts.getOrPut(mangaId to chapterId) { mutableMapOf() }
        return Part(mangaId, chapterId, pages)
    }

    override suspend fun commit(
        part: ChapterDownloadPart,
        expectedPageCount: Int,
    ): DownloadManifest {
        val key = part.mangaId to part.chapterId
        val pages = parts[key].orEmpty().values.sortedBy(DownloadedPage::index)
        check(pages.size == expectedPageCount && pages.mapIndexed { i, page -> page.index == i }.all { it }) {
            "Refusing to commit ${part.chapterId}: expected $expectedPageCount contiguous pages, " +
                "found ${pages.map(DownloadedPage::index)}"
        }
        parts -= key
        return DownloadManifest(
            version = DownloadManifest.CURRENT_VERSION,
            chapterId = part.chapterId,
            pages = pages,
            totalBytes = pages.sumOf(DownloadedPage::bytes),
            downloadedAt = Instant.fromEpochMilliseconds(9_000),
        ).also {
            committed[key] = it
            noteChange()
        }
    }

    override suspend fun delete(
        mangaId: Uuid,
        chapterId: Uuid,
    ) {
        committed -= mangaId to chapterId
        parts -= mangaId to chapterId
        noteChange()
    }

    override suspend fun deleteManga(mangaId: Uuid) {
        committed.keys.filter { it.first == mangaId }.forEach(committed::remove)
        parts.keys.filter { it.first == mangaId }.forEach(parts::remove)
        noteChange()
    }

    override suspend fun deleteAll() {
        committed.clear()
        parts.clear()
        noteChange()
    }

    override suspend fun usedBytes(): Long = committed.values.sumOf(DownloadManifest::totalBytes)

    override suspend fun usedBytes(mangaId: Uuid): Long =
        committed
            .filterKeys { it.first == mangaId }
            .values
            .sumOf(DownloadManifest::totalBytes)

    override suspend fun storedChapters(): List<StoredChapter> =
        committed.keys.map { (manga, chapter) -> StoredChapter(manga, chapter, isComplete = true) } +
            parts.keys.map { (manga, chapter) -> StoredChapter(manga, chapter, isComplete = false) }

    /** Whether a resumable part directory exists — what a failed chapter must leave behind. */
    fun hasPart(
        mangaId: Uuid,
        chapterId: Uuid,
    ): Boolean = parts.containsKey(mangaId to chapterId)

    /** Seeds a part with pages an interrupted attempt is meant to have already fetched. */
    fun seedPart(
        mangaId: Uuid,
        chapterId: Uuid,
        indices: List<Int>,
        bytesPerPage: Long = 8L,
    ) {
        val pages = parts.getOrPut(mangaId to chapterId) { mutableMapOf() }
        indices.forEach { index -> pages[index] = DownloadedPage(index, "00$index.jpg", bytesPerPage) }
    }

    private inner class Part(
        override val mangaId: Uuid,
        override val chapterId: Uuid,
        private val pages: MutableMap<Int, DownloadedPage>,
    ) : ChapterDownloadPart {
        override suspend fun existingPages(): List<DownloadedPage> = pages.values.sortedBy(DownloadedPage::index)

        override suspend fun writePage(
            index: Int,
            extension: String,
            bytes: ByteArray,
        ): DownloadedPage =
            DownloadedPage(index, "00$index.$extension", bytes.size.toLong()).also { pages[index] = it }

        override suspend fun discard() {
            parts -= mangaId to chapterId
            noteChange()
        }
    }
}

/**
 * A [PageFetcher] serving canned bytes, with per-URL scripted failures.
 *
 * Failures are consumed one per attempt, which is what makes "fails twice, then succeeds" — the case the
 * engine's retry exists for — expressible without any notion of time.
 */
internal class FakePageFetcher(
    private val bytesPerPage: Int = 8,
) : PageFetcher {
    /** Every URL asked for, in order, including repeats from a retry. */
    val fetched = mutableListOf<String>()

    /** Scripted failures per URL, popped one per attempt. */
    val failures = mutableMapOf<String, MutableList<DownloadError>>()

    /** Runs before each fetch — the hook tests use to interfere mid-download (cancel, re-enter). */
    var onFetch: suspend (String) -> Unit = {}

    override suspend fun fetch(page: Page): FetchedPage {
        val url = page.imageUrl?.value ?: page.url?.value ?: error("page ${page.index} has no url")
        fetched += url
        onFetch(url)
        failures[url]?.takeIf { it.isNotEmpty() }?.let { scripted ->
            throw PageFetchException(scripted.removeAt(0), "scripted failure for $url")
        }
        return FetchedPage(bytes = ByteArray(bytesPerPage) { it.toByte() }, extension = "jpg")
    }

    /** Scripts [error] for every attempt at [url] — a page that never arrives. */
    fun alwaysFail(
        url: String,
        error: DownloadError,
    ) {
        failures[url] = MutableList(ALWAYS) { error }
    }

    /** Scripts [times] failures at [url], after which it succeeds. */
    fun failTimes(
        url: String,
        times: Int,
        error: DownloadError = DownloadError.Network,
    ) {
        failures[url] = MutableList(times) { error }
    }

    private companion object {
        /** More attempts than the engine will ever make for one page. */
        const val ALWAYS = 100
    }
}

/**
 * A [SettingsRepository] with only the download pause implemented.
 *
 * The interface is wide and every other member belongs to a subsystem none of these tests touch — the
 * theme, the library update schedule, the uninstalled-source set. Faking those would be inventing
 * behaviour nothing here reads, so they fail loudly instead: a test that starts depending on one should
 * say so rather than quietly get a default.
 */
internal class FakeSettingsRepository : SettingsRepository {
    private val paused = MutableStateFlow(false)
    private val autoDownloadMode = MutableStateFlow(AutoDownloadMode.Off)
    private val autoDownloadCategories = MutableStateFlow(emptySet<Uuid>())
    private val wifiOnly = MutableStateFlow(false)
    private val deleteAfterReading = MutableStateFlow(DeleteAfterReading.Off)

    override fun observeDownloadsPaused(): Flow<Boolean> = paused

    override suspend fun setDownloadsPaused(paused: Boolean) {
        this.paused.value = paused
    }

    override fun observeAutoDownloadMode(): Flow<AutoDownloadMode> = autoDownloadMode

    override fun observeAutoDownloadCategories(): Flow<Set<Uuid>> = autoDownloadCategories

    override suspend fun setAutoDownload(
        mode: AutoDownloadMode,
        categories: Set<Uuid>,
    ) {
        autoDownloadMode.value = mode
        autoDownloadCategories.value = categories
    }

    override fun observeDownloadOnWifiOnly(): Flow<Boolean> = wifiOnly

    override suspend fun setDownloadOnWifiOnly(wifiOnly: Boolean) {
        this.wifiOnly.value = wifiOnly
    }

    override fun observeDeleteAfterReading(): Flow<DeleteAfterReading> = deleteAfterReading

    override suspend fun setDeleteAfterReading(setting: DeleteAfterReading) {
        deleteAfterReading.value = setting
    }

    override fun observeThemePreference(): Flow<ThemePreference> = unused()

    override suspend fun setThemePreference(preference: ThemePreference) = unused()

    override fun observeCrashReportingEnabled(): Flow<Boolean> = unused()

    override suspend fun setCrashReportingEnabled(enabled: Boolean) = unused()

    override fun observeLibraryUpdateInterval(): Flow<LibraryUpdateInterval> = unused()

    override suspend fun setLibraryUpdateInterval(interval: LibraryUpdateInterval) = unused()

    override suspend fun getLastLibraryUpdate(): Instant? = unused()

    override suspend fun setLastLibraryUpdate(at: Instant) = unused()

    override suspend fun getRemovedSourceIds(): Set<String> = unused()

    override suspend fun setSourceRemoved(
        sourceId: String,
        removed: Boolean,
    ) = unused()

    private fun unused(): Nothing = error("FakeSettingsRepository only implements the download pause")
}

/**
 * A [CategoryRepository] over an in-memory membership map — the only half the auto-download rule reads.
 */
internal class FakeCategoryRepository : CategoryRepository {
    private val memberships = MutableStateFlow<Map<Uuid, Set<Uuid>>>(emptyMap())

    /** Puts [mangaId] in [categoryIds], the way the category sheet would. */
    fun place(
        mangaId: Uuid,
        categoryIds: Set<Uuid>,
    ) {
        memberships.value = memberships.value + (mangaId to categoryIds)
    }

    override fun observeLibraryCategories(): Flow<Map<Uuid, Set<Uuid>>> = memberships

    override fun observeCategories(): Flow<List<Category>> = flowOf(emptyList())

    override suspend fun setMangaCategories(
        mangaId: Uuid,
        categoryIds: Set<Uuid>,
    ) = place(mangaId, categoryIds)

    override suspend fun upsertCategory(category: Category) = error("unused")

    override suspend fun deleteCategory(categoryId: Uuid) = error("unused")
}

/**
 * A [ConnectionObserver] that is online and unmetered until a test says otherwise — the state almost
 * every test wants, and the one the download rules treat as "just run".
 */
internal class FakeConnectionObserver : ConnectionObserver {
    private val meteredState = MutableStateFlow(false)

    override val connectivity: Flow<ConnectionObserver.ConnectionStatus> =
        MutableStateFlow(ConnectionObserver.ConnectionStatus.ONLINE)

    override val status: ConnectionObserver.ConnectionStatus = ConnectionObserver.ConnectionStatus.ONLINE

    override val metered: Flow<Boolean> = meteredState

    override val isMetered: Boolean get() = meteredState.value

    /** Moves the device onto (or off) a connection the user pays for by the byte. */
    fun setMetered(metered: Boolean) {
        meteredState.value = metered
    }
}

/** Records that the queue was asked to run, without running it. */
internal class FakeDownloadScheduler : DownloadScheduler {
    var starts: Int = 0
        private set

    /** Android's answer, since it is the one that makes the queue survive leaving the app. */
    override val runsInBackground: Boolean = true

    override fun ensureRunning() {
        starts++
    }
}

/**
 * Records what the user would have been told, so the notification contract can be asserted without a
 * platform notification anywhere near the test.
 */
internal class FakeDownloadNotifier : DownloadNotifier {
    val progress = mutableListOf<DownloadProgress>()
    val finished = mutableListOf<Pair<Int, Int>>()
    var clears: Int = 0
        private set

    override suspend fun onProgress(progress: DownloadProgress) {
        this.progress += progress
    }

    override suspend fun onFinished(
        downloaded: Int,
        failed: Int,
    ) {
        finished += downloaded to failed
    }

    override suspend fun clear() {
        clears++
    }
}

/** A [SourceLoader] serving [pagesPerChapter] pages per chapter, recording what it was asked for. */
internal class FakeSourceLoader(
    private val pagesPerChapter: Int,
) : SourceLoader {
    /** Chapter keys asked for, in order — how "the page list is fetched per chapter" is checked. */
    val asked = mutableListOf<String>()

    /** Chapter keys whose page list fails. */
    val failing = mutableSetOf<String>()

    /** Chapter keys the source serves no pages for. */
    val empty = mutableSetOf<String>()

    /** Exact page lists per chapter key, for tests that care what a page carries rather than how many. */
    val scripted = mutableMapOf<String, List<Page>>()

    override suspend fun fetchChapterPages(
        sourceId: String,
        chapterId: String,
    ): List<Page> {
        asked += chapterId
        if (chapterId in failing) error("the source is down")
        if (chapterId in empty) return emptyList()
        scripted[chapterId]?.let { return it }
        return List(pagesPerChapter) { index ->
            Page(
                index = index.toUInt(),
                imageUrl = Url("https://cdn.mangadex.org/$chapterId/$index.jpg"),
                url = null,
            )
        }
    }

    /** The URL this fake serves for one page, so a test can script a failure for it. */
    fun pageUrl(
        chapterKey: String,
        index: Int,
    ): String = "https://cdn.mangadex.org/$chapterKey/$index.jpg"

    override suspend fun loadSource(id: String): Source = error("unused")

    override fun observeInstalledSources(): Flow<List<SourceMeta>> = error("unused")

    override suspend fun fetchMangaList(
        sourceId: String,
        listing: SourceListing?,
        query: String?,
        page: Int,
    ): MangaPage = error("unused")

    override suspend fun fetchMangaDetails(
        sourceId: String,
        mangaKey: String,
    ): MangaListing = error("unused")

    override suspend fun fetchChapterList(
        sourceId: String,
        mangaKey: String,
    ): List<ChapterListing> = error("unused")
}

/** Chapters held by id, with the two reads the download path makes. */
internal class FakeChapterRepository(
    chapters: List<Chapter>,
) : ChapterRepository {
    private val held = chapters.associateByTo(mutableMapOf(), Chapter::id)

    fun replace(chapter: Chapter) {
        held[chapter.id] = chapter
    }

    override suspend fun getChapter(chapterId: Uuid): Chapter? = held[chapterId]

    override suspend fun getChaptersByIds(chapterIds: Collection<Uuid>): List<Chapter> =
        chapterIds.mapNotNull(held::get)

    override suspend fun getChapters(mangaId: Uuid): List<Chapter> =
        held.values
            .filter { it.mangaId == mangaId }
            .sortedBy { it.chapterNumber?.value }

    override fun observeChapters(mangaId: Uuid): Flow<List<Chapter>> = flowOf(held.values.toList())

    override fun observeBookmarkedChapters(mangaId: Uuid): Flow<List<Chapter>> = flowOf(emptyList())

    override suspend fun updateProgress(
        chapterId: Uuid,
        progress: ReadingProgress,
    ) = error("unused")

    override suspend fun updateProgress(progress: Map<Uuid, ReadingProgress>) = error("unused")

    override suspend fun upsertChapter(chapter: Chapter) = error("unused")

    override suspend fun upsertChapters(chapters: List<Chapter>) = error("unused")

    override suspend fun deleteChapter(chapterId: Uuid) = error("unused")

    override suspend fun deleteChapters(chapterIds: List<Uuid>) = error("unused")
}

/** One manga, observable and replaceable — the scanlator filter is read through it. */
internal class FakeMangaRepository(
    manga: Manga,
) : MangaRepository {
    // A list rather than the one series the download tests started with: the source-scoped use cases ask
    // "which series belong to this source", and a fixture that can only hold one can't tell a right
    // answer from an answer that ignored the question.
    private val held = MutableStateFlow(listOf(manga))

    fun replace(manga: Manga) {
        held.update { all -> all.map { if (it.id == manga.id) manga else it } }
    }

    /** Adds another series, for the tests that need two. */
    fun add(manga: Manga) {
        held.update { it + manga }
    }

    override fun observeManga(mangaId: Uuid): Flow<Manga?> = held.map { all -> all.firstOrNull { it.id == mangaId } }

    override suspend fun getMangaByIds(mangaIds: Collection<Uuid>): List<Manga> =
        held.value.filter { it.id in mangaIds }

    override fun observeLibrary(): Flow<List<Manga>> = held

    override fun observeMangaBySource(sourceId: Uuid): Flow<List<Manga>> =
        held.map { all -> all.filter { it.source == sourceId } }

    override suspend fun getMangaIdBySourceAndKey(
        sourceId: Uuid,
        sourceMangaKey: String,
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

    override suspend fun markChaptersFetched(mangaId: Uuid, at: Instant) = error("unused")

    override suspend fun markDetailsFetched(mangaId: Uuid, at: Instant) = error("unused")

    override suspend fun setFilteredScanlators(
        mangaId: Uuid,
        scanlators: Set<String>,
    ) = error("unused")

    override suspend fun setReadingStatus(
        mangaId: Uuid,
        status: ReadingStatus,
    ) = error("unused")

    override suspend fun setReadingMode(
        mangaId: Uuid,
        mode: ReadingMode,
    ) = error("unused")

    override suspend fun deleteManga(mangaId: Uuid) = error("unused")

    override suspend fun addToLibrary(mangaId: Uuid) = error("unused")

    /** Records the removal, so the cascade tests can prove the manga row survives it. */
    override suspend fun removeFromLibrary(mangaId: Uuid) {
        held.update { all -> all.map { if (it.id == mangaId) it.copy(dateAdded = null) else it } }
    }
}

/** The one installed source every chapter resolves through. */
internal class FakeSourceRepository(
    private val source: Source,
) : SourceRepository {
    override fun observeSources(): Flow<List<Source>> = flowOf(listOf(source))

    override suspend fun getSource(id: Uuid): Source? = source.takeIf { it.id == id }

    override suspend fun getSourceByNativeId(sourceId: String): Source? = source.takeIf { it.sourceId == sourceId }

    override suspend fun addSource(source: Source) = error("unused")

    override suspend fun deleteSource(source: Source) = error("unused")
}

/** The pending queue as it stands, for tests that assert on ordering. */
internal suspend fun DownloadRepository.pending(): List<ChapterDownload> =
    observeQueue().first().filter(ChapterDownload::isPending)
/**
 * A [DeleteReadChapterDownloads] wired to real fakes but **switched off** — the setting defaults to
 * `Off`, so it returns before touching anything else.
 *
 * For the tests of the use cases that *call* it. Those cover marking chapters read, not deleting
 * downloads, and a sealed use case cannot be doubled from a test compilation — so the honest stand-in is
 * the real thing in the state it ships in.
 */
internal fun inertDeleteReadChapterDownloads(): DeleteReadChapterDownloads =
    DownloadWorld(TestDispatchers(Dispatchers.Unconfined)).deleteReadDownloads

/** The same, for [AutoDownloadNewChapters]: `AutoDownloadMode.Off` is its default and its early return. */
internal fun inertAutoDownloadNewChapters(): AutoDownloadNewChapters =
    DownloadWorld(TestDispatchers(Dispatchers.Unconfined)).autoDownload

/**
 * The same, for [DeleteChapterDownloads] — for the tests of [SyncSourceChapters], which calls it when a
 * source stops listing a chapter.
 *
 * Inert by *situation* rather than by setting: it is wired to an empty world, so every id it is handed
 * has no record and nothing to delete. That is also the true state of the fixtures using it, none of
 * which download anything.
 */
internal fun inertDeleteChapterDownloads(): DeleteChapterDownloads =
    DownloadWorld(TestDispatchers(Dispatchers.Unconfined)).deleteChapters

/** The same, for [DeleteMangaDownloads] — for the tests of `RemoveMangaFromLibrary`. */
internal fun inertDeleteMangaDownloads(): DeleteMangaDownloads =
    DownloadWorld(TestDispatchers(Dispatchers.Unconfined)).deleteManga
