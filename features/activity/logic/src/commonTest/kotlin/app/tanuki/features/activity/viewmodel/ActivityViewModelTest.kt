package app.tanuki.features.activity.viewmodel

import app.tanuki.core.domain.chapter.model.Chapter
import app.tanuki.core.domain.chapter.model.ChapterNumber
import app.tanuki.core.domain.common.model.Text
import app.tanuki.core.domain.common.model.Url
import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.common.service.LogPriority
import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.domain.history.model.HistoryEntry
import app.tanuki.core.domain.history.model.MostReadManga
import app.tanuki.core.domain.history.model.ReadingActivityPoint
import app.tanuki.core.domain.history.model.ReadingStats
import app.tanuki.core.domain.history.model.TimePeriod
import app.tanuki.core.domain.history.usecase.DeleteHistory
import app.tanuki.core.domain.history.usecase.GetMostRead
import app.tanuki.core.domain.history.usecase.GetReadingActivity
import app.tanuki.core.domain.history.usecase.ObserveReadHistory
import app.tanuki.core.domain.history.usecase.ObserveReadingStats
import app.tanuki.core.domain.manga.model.Manga
import app.tanuki.core.domain.manga.model.MangaCover
import app.tanuki.core.domain.manga.model.PublishStatus
import app.tanuki.core.domain.manga.model.ReadingMode
import app.tanuki.core.domain.manga.model.ReadingStatus
import app.tanuki.features.activity.viewmodel.ActivityState.Loaded
import app.tanuki.features.activity.viewmodel.ActivityState.Loading
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import app.tanuki.core.navigation.MangaDetail
import app.tanuki.core.navigation.NavEvent
import app.tanuki.core.navigation.Reader
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Covers the period switch — the one thing this screen's pipeline does that a plain `combine` would get
 * wrong — and the subscription gate the `StateViewModel` migration introduced. This tab is contributed
 * to `AppScope`, so before that gate its four-way fan-out over the whole history table ran for the life
 * of the process, including while the user was in the reader.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActivityViewModelTest {
    private val mainDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the history query is not run until the screen is watching`() = runTest {
        val world = World()
        val viewModel = world.viewModel()

        runCurrent()

        assertEquals(0, world.historySubscriptions)
        assertEquals(Loading, viewModel.uiState.value)
    }

    @Test
    fun `the screen loads on the default period`() = runTest {
        val world = World()
        val viewModel = world.viewModel()

        val job = collect(viewModel)

        val state = viewModel.uiState.value
        assertTrue(state is Loaded)
        assertEquals(TimePeriod.WEEK, state.stats.period)
        assertEquals(ActivityTab.History, state.tab)
        job.cancel()
    }

    @Test
    fun `switching the period re-derives the curve and the leaderboard with it`() = runTest {
        val world = World()
        val viewModel = world.viewModel()
        val job = collect(viewModel)

        viewModel.onPeriodChange(TimePeriod.YEAR)
        runCurrent()

        val state = viewModel.uiState.value
        assertTrue(state is Loaded)
        // The label, the curve and the leaderboard must all be the *same* period's — that pairing is
        // what the flatMapLatest exists for.
        assertEquals(TimePeriod.YEAR, state.stats.period)
        assertEquals(listOf(TimePeriod.YEAR.ordinal) + List(11) { 0 }, state.stats.bars.map { it.count })
        assertEquals(TimePeriod.YEAR.ordinal, state.stats.mostRead.first().count)
        job.cancel()
    }

    @Test
    fun `the query is torn down once the screen stops watching`() = runTest {
        val world = World()
        val viewModel = world.viewModel()

        val job = collect(viewModel)
        assertEquals(1, world.historySubscriptions)
        job.cancel()
        advanceTimeBy(STOP_TIMEOUT + 1)
        collect(viewModel).cancel()

        assertEquals(2, world.historySubscriptions)
    }

    @Test
    fun `a series read several times in a day is one row - other days stay apart`() {
        val eleceed = manga("Eleceed")
        val kingdom = manga("Kingdom")
        val entries =
            listOf(
                entry(eleceed, 418.0, at(10, 9, 20, 5)),
                entry(eleceed, 421.0, at(10, 9, 23, 40)),
                entry(eleceed, 419.0, at(10, 9, 21, 0)),
                entry(kingdom, 860.0, at(10, 9, 22, 0)),
                entry(eleceed, 417.0, at(10, 8, 12, 0))
            )

        val days = entries.toDays(today = LocalDate(2026, 10, 11))

        assertEquals(listOf(2, 3), days.map { it.daysAgo })
        val rows = days.first().rows
        assertEquals(listOf("Eleceed", "Kingdom"), rows.map { it.title })
        val merged = rows.first()
        assertEquals(3, merged.chapterCount)
        assertEquals("418" to "421", merged.firstChapter to merged.lastChapter)
        assertEquals(23 to 40, merged.hour to merged.minute)
        assertEquals(entries[1].chapter?.id, merged.resumeChapterId)
        assertEquals(1, days.last().rows.single().chapterCount)
    }

    @Test
    fun `a row resumes its latest chapter and delete forgets every read it merged`() = runTest {
        val world = World()
        val eleceed = manga("Eleceed")
        world.history.value = listOf(entry(eleceed, 1.0, at(10, 9, 20, 0)), entry(eleceed, 2.0, at(10, 9, 21, 0)))
        val viewModel = world.viewModel()
        val job = collect(viewModel)
        val events = mutableListOf<NavEvent>()
        val nav = launch { viewModel.navEvents.collect { events += it } }
        val row = (viewModel.uiState.value as Loaded).history.single().rows.single()

        viewModel.onRowClick(row)
        viewModel.onOpenSeries(row.mangaId)
        viewModel.onDeleteRow(row)
        runCurrent()

        assertEquals(
            listOf(Reader(eleceed.id, world.history.value[1].chapter!!.id), MangaDetail(eleceed.id)),
            events.map { (it as NavEvent.NavTo).destination }
        )
        assertEquals(world.history.value.map { it.id }.toSet(), world.deleted.toSet())
        nav.cancel()
        job.cancel()
    }

    @Test
    fun `the tab lives in state and rank changes are words`() = runTest {
        val world = World()
        val viewModel = world.viewModel()
        val job = collect(viewModel)

        viewModel.onTabChange(ActivityTab.Statistics)
        runCurrent()

        val state = viewModel.uiState.value as Loaded
        assertEquals(ActivityTab.Statistics, state.tab)
        assertEquals(
            listOf(RankChange.Up(2), RankChange.Same, RankChange.New, RankChange.Down(3)),
            state.stats.mostRead.map { it.change }
        )
        assertEquals(AllTimeStats(chapters = 979, series = 30, currentStreak = 0, bestStreak = 12), state.stats.allTime)
        job.cancel()
    }

    @Test
    fun `period numbers count reading days out of the days so far`() = runTest {
        val world = World()
        world.activity = listOf(3, 0, 5, 0, 1)
        val viewModel = world.viewModel()
        val job = collect(viewModel)

        val stats = (viewModel.uiState.value as Loaded).stats
        assertEquals(9, stats.periodChapters)
        assertEquals(3 to 5, stats.activeDays to stats.elapsedDays)
        // The week runs on past today with empty bars, so the chart always spans all seven days.
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), stats.bars.map { it.day })
        assertEquals(listOf(3, 0, 5, 0, 1, 0, 0), stats.bars.map { it.count })
        job.cancel()
    }

    @Test
    fun `a month and a year span their whole length from their first bar`() {
        val october = listOf(ReadingActivityPoint(LocalDate(2026, 10, 1), 2), ReadingActivityPoint(LocalDate(2026, 10, 2), 0))
        val year = listOf(ReadingActivityPoint(LocalDate(2026, 1, 1), 4))

        assertEquals(31, october.toBars(TimePeriod.MONTH).size)
        assertEquals((1..12).toList(), year.toBars(TimePeriod.YEAR).map { it.month })
        assertEquals(listOf(4) + List(11) { 0 }, year.toBars(TimePeriod.YEAR).map { it.count })
    }

    private fun TestScope.collect(viewModel: ActivityViewModel): Job {
        val job = launch { viewModel.uiState.collect {} }
        runCurrent()
        return job
    }

    private companion object {
        /** Mirrors `TanukiViewModel.STOP_TIMEOUT_MS`, which is private to the base. */
        const val STOP_TIMEOUT = 5_000L
    }

    private class World {
        var historySubscriptions = 0
            private set

        val history = MutableStateFlow(emptyList<HistoryEntry>())
        val deleted = mutableListOf<Uuid>()

        /** Per-day counts the period's activity reports; `null` = one point holding the period's ordinal. */
        var activity: List<Int>? = null

        fun viewModel(): ActivityViewModel =
            ActivityViewModel(
                observeReadHistory = FakeObserveReadHistory(history.onStart { historySubscriptions++ }),
                observeReadingStats = FakeObserveReadingStats,
                getReadingActivity =
                    object : GetReadingActivity {
                        override fun invoke(period: TimePeriod): Flow<List<ReadingActivityPoint>> =
                            flowOf(
                                activity?.mapIndexed { i, count -> ReadingActivityPoint(LocalDate(2026, 10, i + 1), count) }
                                    ?: listOf(ReadingActivityPoint(date = LocalDate(2026, 1, 1), count = period.ordinal))
                            )
                    },
                getMostRead = FakeGetMostRead,
                deleteHistory =
                    object : DeleteHistory {
                        override suspend fun invoke(entry: HistoryEntry) {
                            deleted += entry.id
                        }
                    },
                dispatcher = ImmediateDispatchers,
                logger = SilentLogger
            )
    }
}

private class FakeObserveReadHistory(
    private val history: Flow<List<HistoryEntry>>
) : ObserveReadHistory {
    override fun invoke(): Flow<List<HistoryEntry>> = history
}

private object FakeObserveReadingStats : ObserveReadingStats {
    override fun invoke(): Flow<ReadingStats> =
        flowOf(
            ReadingStats(totalChapters = 979, seriesCount = 30, dayStreak = 0, bestStreak = 12)
        )
}

/** Tags its answer with the period it was asked for, so a test can tell the two apart. */
private object FakeGetMostRead : GetMostRead {
    override fun invoke(period: TimePeriod): Flow<List<MostReadManga>> =
        flowOf(
            listOf(
                MostReadManga(manga = manga(), readCount = period.ordinal, previousRank = 3),
                MostReadManga(manga = manga(), readCount = 1, previousRank = 2),
                MostReadManga(manga = manga(), readCount = 1, previousRank = null),
                MostReadManga(manga = manga(), readCount = 1, previousRank = 1)
            )
        )
}

private fun manga(
    title: String = "Berserk",
    id: Uuid = Uuid.random()
): Manga =
    Manga(
        id = id,
        source = Uuid.random(),
        title = Text(title),
        author = null,
        description = null,
        cover = MangaCover(mangaId = id, url = null),
        url = Url("https://example.com/berserk"),
        dateAdded = null,
        readingMode = ReadingMode.RIGHT_TO_LEFT,
        publishStatus = PublishStatus.ONGOING,
        readingStatus = ReadingStatus.READING
    )

/** 2026 at local [month]/[day] [hour]:[minute] — local, as the screen groups by the device's days. */
private fun at(
    month: Int,
    day: Int,
    hour: Int,
    minute: Int
): Instant = LocalDateTime(2026, month, day, hour, minute).toInstant(TimeZone.currentSystemDefault())

private fun entry(
    manga: Manga,
    number: Double,
    readAt: Instant
): HistoryEntry {
    val chapter =
        Chapter(
            id = Uuid.random(),
            mangaId = manga.id,
            chapterNumber = ChapterNumber(number),
            name = null,
            scanlator = null,
            sourceChapterKey = null,
            url = Url("https://example.com/$number"),
            dateUpload = null,
            dateFetch = readAt,
            progress = null
        )
    return HistoryEntry(id = Uuid.random(), chapterId = chapter.id, manga = manga, chapter = chapter, readAt = readAt)
}

private object ImmediateDispatchers : DispatcherProvider {
    override val default: CoroutineDispatcher get() = Dispatchers.Main
    override val io: CoroutineDispatcher get() = Dispatchers.Main
    override val main: CoroutineDispatcher get() = Dispatchers.Main
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
