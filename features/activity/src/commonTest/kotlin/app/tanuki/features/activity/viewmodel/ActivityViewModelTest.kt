package app.tanuki.features.activity.viewmodel

import app.tanuki.core.domain.common.model.Text
import app.tanuki.core.domain.common.model.Url
import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.common.service.LogPriority
import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.domain.history.model.HistoryEntry
import app.tanuki.core.domain.history.model.MostReadManga
import app.tanuki.core.domain.history.model.MostReadManga.ReadTrend
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
        assertEquals(TimePeriod.WEEK, state.period)
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
        assertEquals(TimePeriod.YEAR, state.period)
        assertEquals(listOf(TimePeriod.YEAR.ordinal), state.activity.map { it.count })
        assertEquals(listOf(TimePeriod.YEAR.ordinal), state.mostRead.map { it.readCount })
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

        private val history = MutableStateFlow(emptyList<HistoryEntry>())

        fun viewModel(): ActivityViewModel =
            ActivityViewModel(
                observeReadHistory = FakeObserveReadHistory(history.onStart { historySubscriptions++ }),
                observeReadingStats = FakeObserveReadingStats,
                getReadingActivity = FakeGetReadingActivity,
                getMostRead = FakeGetMostRead,
                deleteHistory = FakeDeleteHistory,
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
            ReadingStats(
                totalChapters = 0,
                seriesCount = 0,
                dayStreak = 0,
                distribution = emptyList(),
                categoryShares = emptyList()
            )
        )
}

/** Tags its answer with the period it was asked for, so a test can tell the two apart. */
private object FakeGetReadingActivity : GetReadingActivity {
    override fun invoke(period: TimePeriod): Flow<List<ReadingActivityPoint>> =
        flowOf(listOf(ReadingActivityPoint(date = LocalDate(2026, 1, 1), count = period.ordinal)))
}

private object FakeGetMostRead : GetMostRead {
    override fun invoke(period: TimePeriod): Flow<List<MostReadManga>> =
        flowOf(listOf(MostReadManga(manga = manga(), readCount = period.ordinal, trend = ReadTrend.FLAT)))
}

private fun manga(id: Uuid = Uuid.random()): Manga =
    Manga(
        id = id,
        source = Uuid.random(),
        title = Text("Berserk"),
        author = null,
        description = null,
        cover = MangaCover(mangaId = id, url = null),
        url = Url("https://example.com/berserk"),
        dateAdded = null,
        readingMode = ReadingMode.RIGHT_TO_LEFT,
        publishStatus = PublishStatus.ONGOING,
        readingStatus = ReadingStatus.READING
    )

private object FakeDeleteHistory : DeleteHistory {
    override suspend fun invoke(entry: HistoryEntry) = Unit
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
