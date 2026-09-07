package app.tanuki.core.domain.update.usecase

import app.tanuki.core.domain.chapter.model.Chapter
import app.tanuki.core.domain.chapter.model.ChapterNumber
import app.tanuki.core.domain.chapter.model.ReadingProgress
import app.tanuki.core.domain.chapter.repository.ChapterRepository
import app.tanuki.core.domain.common.model.Text
import app.tanuki.core.domain.common.model.Url
import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.common.service.uuidV7
import app.tanuki.core.domain.manga.model.Manga
import app.tanuki.core.domain.manga.model.MangaCover
import app.tanuki.core.domain.manga.model.PublishStatus
import app.tanuki.core.domain.manga.model.ReadingMode
import app.tanuki.core.domain.manga.model.ReadingStatus
import app.tanuki.core.domain.manga.repository.MangaRepository
import app.tanuki.core.domain.update.model.ChapterUpdate
import app.tanuki.core.domain.update.model.NewChapter
import app.tanuki.core.domain.update.repository.ChapterUpdateRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The list of pending updates has to stay true while it is on screen, and every rule that decides
 * whether a chapter is still pending lives in a table `chapter_updates` is not written with — the user
 * opens a chapter (`reading_progress`), hides a group (`manga`), unfollows a series (`library`). So
 * every case here changes one of *those* and asserts the list corrects itself, **without a single write
 * to the records**. That is the whole of efficiency-backlog item 2.2: before it, the wrapper resolved
 * against snapshots and none of these passed.
 *
 * The fakes are [MutableStateFlow]-backed rather than `flowOf`, since a static fake cannot express
 * "and then it changed" — which is the only thing being tested.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ObserveNewChaptersTest {
    @Test
    fun `reading a chapter drops it without any record being rewritten`() =
        runTest {
            val world = World(this)

            world.chapters.replace(CHAPTER.copy(progress = OPENED))
            advanceUntilIdle()

            assertEquals(emptyList(), world.latest(), "the reader's progress row is the dismissal")
        }

    @Test
    fun `hiding a scanlation group drops its chapter with no record rewritten`() =
        runTest {
            val world = World(this, chapter = CHAPTER.copy(scanlator = Text("Mangastream")))

            world.manga.replace(MANGA.copy(filteredScanlators = setOf("Mangastream")))
            advanceUntilIdle()

            assertEquals(emptyList(), world.latest())
        }

    @Test
    fun `a series the user unfollows loses its pending updates`() =
        runTest {
            val world = World(this)

            world.manga.replace(MANGA.copy(dateAdded = null))
            advanceUntilIdle()

            assertEquals(emptyList(), world.latest())
        }

    @Test
    fun `a renamed series re-emits under its new title`() =
        runTest {
            val world = World(this)

            world.manga.replace(MANGA.copy(title = Text("Berserk")))
            advanceUntilIdle()

            // Not just a filter: the rows themselves are live, so the list cannot show a stale title.
            assertEquals(listOf("Berserk"), world.latest().map { it.manga.title.value })
        }

    @Test
    fun `a newly discovered record is resolved`() =
        runTest {
            val world = World(this, updates = emptyList())
            assertEquals(emptyList(), world.latest())

            world.updates.replace(listOf(UPDATE))
            advanceUntilIdle()

            assertEquals(listOf(CHAPTER.id), world.latest().map { it.chapter.id })
        }

    @Test
    fun `marking the records notified does not resubscribe the resolution`() =
        runTest {
            val world = World(this)
            assertEquals(1, world.manga.subscriptions)

            // `notified` is part of a record but not of what the resolver reads, and NotifyNewChapters
            // flips it after every library run — the gate is keyed on chapter ids so that write cannot
            // tear down and rebuild both queries for a byte-identical answer.
            world.updates.replace(listOf(UPDATE.copy(notified = true)))
            advanceUntilIdle()

            assertEquals(1, world.manga.subscriptions)
            assertEquals(listOf(CHAPTER.id), world.latest().map { it.chapter.id })
        }

    @Test
    fun `an emptied record list settles on nothing rather than never answering`() =
        runTest {
            val world = World(this)

            world.updates.replace(emptyList())
            advanceUntilIdle()

            assertEquals(emptyList(), world.latest())
        }

    // --- helpers ---------------------------------------------------------------------------------

    /** The three repositories behind the use case, all live, with the emissions collected. */
    private class World(
        scope: TestScope,
        manga: Manga = MANGA,
        chapter: Chapter = CHAPTER,
        updates: List<ChapterUpdate> = listOf(UPDATE),
    ) {
        val manga = LiveMangaRepository(manga)
        val chapters = LiveChapterRepository(chapter)
        val updates = LiveChapterUpdateRepository(updates)

        private val emissions = mutableListOf<List<NewChapter>>()

        init {
            val dispatcher = UnconfinedTestDispatcher(scope.testScheduler)
            val observe =
                ObserveNewChapters.Default(
                    chapterUpdateRepository = this.updates,
                    resolveChapterUpdates = ResolveChapterUpdates(this.manga, chapters),
                    dispatchers = FixedDispatchers(dispatcher),
                )
            scope.backgroundScope.launch(dispatcher) { observe().collect { emissions += it } }
            scope.testScheduler.advanceUntilIdle()
        }

        fun latest(): List<NewChapter> = emissions.last()
    }

    private companion object {
        val DISCOVERED_AT = Instant.fromEpochMilliseconds(1_700_000_000_000)
        val OPENED = ReadingProgress(hasBookmark = false, lastPageRead = 3u, alreadyRead = false)

        val MANGA = Manga(
            id = uuidV7(),
            source = uuidV7(),
            title = Text("One Piece"),
            author = null,
            description = null,
            cover = MangaCover(mangaId = uuidV7(), url = null, lastModified = Clock.System.now()),
            sourceMangaKey = Text("op-id"),
            url = Url("https://mangadex.org/title/op-id"),
            dateAdded = Instant.fromEpochMilliseconds(1_000),
            readingMode = ReadingMode.LEFT_TO_RIGHT,
            publishStatus = PublishStatus.ONGOING,
            readingStatus = ReadingStatus.READING,
        )

        val CHAPTER = Chapter(
            id = uuidV7(),
            mangaId = MANGA.id,
            chapterNumber = ChapterNumber.orNull(1105.0),
            name = null,
            scanlator = null,
            sourceChapterKey = Text("md-1105"),
            url = MANGA.url,
            dateUpload = null,
            dateFetch = DISCOVERED_AT,
            progress = null,
        )

        val UPDATE = ChapterUpdate(chapterId = CHAPTER.id, mangaId = MANGA.id, discoveredAt = DISCOVERED_AT)
    }
}

/** Runs everything on the test dispatcher, so `flowOn(io)` doesn't leave the scheduler's control. */
private class FixedDispatchers(private val dispatcher: CoroutineDispatcher) : DispatcherProvider {
    override val main: CoroutineDispatcher get() = dispatcher
    override val io: CoroutineDispatcher get() = dispatcher
    override val default: CoroutineDispatcher get() = dispatcher
}

/** One series the test can swap out under the subscription, counting how often it is subscribed. */
private class LiveMangaRepository(manga: Manga) : MangaRepository {
    private val held = MutableStateFlow(manga)

    var subscriptions: Int = 0
        private set

    fun replace(manga: Manga) {
        held.value = manga
    }

    override fun observeMangaByIds(mangaIds: Collection<Uuid>): Flow<List<Manga>> =
        held.map { m -> listOf(m).filter { it.id in mangaIds } }.onStart { subscriptions++ }

    override suspend fun getMangaByIds(mangaIds: Collection<Uuid>): List<Manga> =
        listOf(held.value).filter { it.id in mangaIds }

    override fun observeManga(mangaId: Uuid): Flow<Manga?> = error("unused")
    override fun observeLibrary(): Flow<List<Manga>> = error("unused")
    override fun observeMangaBySource(sourceId: Uuid): Flow<List<Manga>> = error("unused")
    override suspend fun getMangaIdBySourceAndKey(sourceId: Uuid, sourceMangaKey: String): Uuid? = error("unused")
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
    ) = error("unused")

    override suspend fun markDetailsFetched(mangaId: Uuid, at: Instant) = error("unused")
    override suspend fun deleteManga(mangaId: Uuid) = error("unused")
    override suspend fun setFilteredScanlators(mangaId: Uuid, scanlators: Set<String>) = error("unused")
    override suspend fun setReadingStatus(mangaId: Uuid, status: ReadingStatus) = error("unused")
    override suspend fun setReadingMode(mangaId: Uuid, mode: ReadingMode) = error("unused")
    override suspend fun addToLibrary(mangaId: Uuid) = error("unused")
    override suspend fun removeFromLibrary(mangaId: Uuid) = error("unused")
}

/** One chapter the test can swap out under the subscription — standing in for the reader writing progress. */
private class LiveChapterRepository(chapter: Chapter) : ChapterRepository {
    private val held = MutableStateFlow(chapter)

    fun replace(chapter: Chapter) {
        held.value = chapter
    }

    override fun observeChaptersByIds(chapterIds: Collection<Uuid>): Flow<List<Chapter>> =
        held.map { c -> listOf(c).filter { it.id in chapterIds } }

    override suspend fun getChaptersByIds(chapterIds: Collection<Uuid>): List<Chapter> =
        listOf(held.value).filter { it.id in chapterIds }

    override fun observeChapters(mangaId: Uuid): Flow<List<Chapter>> = error("unused")
    override fun observeBookmarkedChapters(mangaId: Uuid): Flow<List<Chapter>> = error("unused")
    override suspend fun getChapters(mangaId: Uuid): List<Chapter> = error("unused")
    override suspend fun getChapter(chapterId: Uuid): Chapter? = error("unused")
    override suspend fun updateProgress(chapterId: Uuid, progress: ReadingProgress) = error("unused")
    override suspend fun updateProgress(progress: Map<Uuid, ReadingProgress>) = error("unused")
    override suspend fun upsertChapter(chapter: Chapter) = error("unused")
    override suspend fun upsertChapters(chapters: List<Chapter>) = error("unused")
    override suspend fun deleteChapter(chapterId: Uuid) = error("unused")
    override suspend fun deleteChapters(chapterIds: List<Uuid>) = error("unused")
}

/** The records, driven by hand — the only table the old implementation reacted to. */
private class LiveChapterUpdateRepository(updates: List<ChapterUpdate>) : ChapterUpdateRepository {
    private val held = MutableStateFlow(updates)

    fun replace(updates: List<ChapterUpdate>) {
        held.value = updates
    }

    override fun observeUpdates(): Flow<List<ChapterUpdate>> = held

    override suspend fun getUnnotified(): List<ChapterUpdate> = error("unused")
    override suspend fun recordUpdates(updates: List<ChapterUpdate>) = error("unused")
    override suspend fun markNotified(chapterIds: List<Uuid>) = error("unused")
    override suspend fun deleteForManga(mangaId: Uuid) = error("unused")
    override suspend fun deleteChaptersForManga(mangaId: Uuid, chapterIds: List<Uuid>) = error("unused")
}
