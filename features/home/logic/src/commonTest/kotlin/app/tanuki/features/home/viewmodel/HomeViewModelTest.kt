package app.tanuki.features.home.viewmodel

import app.tanuki.core.domain.common.model.Text
import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.common.service.LogPriority
import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.domain.history.model.HistoryEntry
import app.tanuki.core.domain.history.usecase.ObserveLibraryReadingHistory
import app.tanuki.core.domain.settings.usecase.ObserveShowSensitiveContent
import app.tanuki.core.domain.source.model.SourceMeta
import app.tanuki.core.domain.source.usecase.GetInstalledSources
import app.tanuki.core.domain.source.usecase.ObserveVisibleSources
import app.tanuki.core.domain.update.model.NewChapter
import app.tanuki.core.domain.update.usecase.ObserveNewChapters
import app.tanuki.features.home.viewmodel.HomeState.Content
import app.tanuki.features.home.viewmodel.HomeState.Loading
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Covers the enumeration gate, which is what the `StateViewModel` migration had to preserve: the
 * installed sources used to be mirrored into a long-lived field so that "not enumerated yet" stayed
 * distinguishable from "enumerated, and there are none". Nothing stands in for that now — `combine`
 * emits only once every arm has, and the real loader is silent until it has read the bundles. These
 * cases are what says that gate is real rather than incidental.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
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
    fun `the screen stays loading until the sources have been enumerated`() = runTest {
        val world = World()
        val viewModel = world.viewModel()

        val job = collect(viewModel)

        assertEquals(Loading, viewModel.uiState.value)
        job.cancel()
    }

    @Test
    fun `an enumeration finding nothing still reaches content`() = runTest {
        val world = World()
        val viewModel = world.viewModel()
        val job = collect(viewModel)

        world.sources.value = emptyList()
        runCurrent()

        val state = viewModel.uiState.value
        assertTrue(state is Content)
        assertTrue(state.sourceGroups.isEmpty())
        job.cancel()
    }

    @Test
    fun `enumerated sources are bucketed by language`() = runTest {
        val world = World()
        val viewModel = world.viewModel()
        val job = collect(viewModel)

        world.sources.value = listOf(source("mangadex", "en"), source("mangafire", "en"), source("br", "pt-BR"))
        runCurrent()

        val state = viewModel.uiState.value
        assertTrue(state is Content)
        assertEquals(listOf("en", "pt-BR"), state.sourceGroups.map { it.lang })
        assertEquals(listOf(2, 1), state.sourceGroups.map { it.sources.size })
        job.cancel()
    }

    @Test
    fun `adult-only sources stay hidden until sensitive content is allowed`() = runTest {
        val world = World()
        val viewModel = world.viewModel()
        val job = collect(viewModel)
        val names = { (viewModel.uiState.value as Content).sourceGroups.flatMap { g -> g.sources.map { it.name } } }

        world.sources.value = listOf(source("mangadex", "en"), source("adult", "en", nsfw = true))
        runCurrent()
        assertEquals(listOf("mangadex"), names())

        world.showSensitive.value = true
        runCurrent()
        assertEquals(listOf("adult", "mangadex"), names().sorted())
        job.cancel()
    }

    @Test
    fun `a language group opens on tap and closes on a second one`() = runTest {
        val world = World()
        val viewModel = world.viewModel()
        val job = collect(viewModel)
        world.sources.value = listOf(source("mangadex", "en"))
        runCurrent()

        viewModel.onToggleLanguage("en")
        runCurrent()
        assertTrue((viewModel.uiState.value as Content).sourceGroups.single().expanded)

        viewModel.onToggleLanguage("en")
        runCurrent()
        assertTrue(!(viewModel.uiState.value as Content).sourceGroups.single().expanded)
        job.cancel()
    }

    @Test
    fun `the screen opens on For You and switches view on select`() = runTest {
        val world = World()
        val viewModel = world.viewModel()
        val job = collect(viewModel)
        world.sources.value = emptyList()
        runCurrent()
        assertEquals(HomeSegment.ForYou, (viewModel.uiState.value as Content).segment)

        viewModel.onSelectSegment(HomeSegment.Sources)
        runCurrent()
        assertEquals(HomeSegment.Sources, (viewModel.uiState.value as Content).segment)
        job.cancel()
    }

    @Test
    fun `the sources already on screen survive the pipeline stopping and restarting`() = runTest {
        val world = World()
        val viewModel = world.viewModel()
        val first = collect(viewModel)
        world.sources.value = listOf(source("mangadex", "en"))
        runCurrent()
        first.cancel()

        // Past the sharing timeout, so the pipeline is torn down and its leading `null` replayed.
        advanceTimeBy(STOP_TIMEOUT + 1)
        val second = collect(viewModel)

        assertEquals(2, world.getInstalledSources.subscriptions)
        val state = viewModel.uiState.value
        assertTrue(state is Content)
        assertEquals(listOf("en"), state.sourceGroups.map { it.lang })
        second.cancel()
    }

    private fun TestScope.collect(viewModel: HomeViewModel): Job {
        val job = launch { viewModel.uiState.collect {} }
        runCurrent()
        return job
    }

    private companion object {
        /** Mirrors `TanukiViewModel.STOP_TIMEOUT_MS`, which is private to the base. */
        const val STOP_TIMEOUT = 5_000L
    }

    private class World {
        /** `null` until enumerated — the fake mirrors the runtime, which answers nothing until it has read the bundles. */
        val sources = MutableStateFlow<List<SourceMeta>?>(null)
        val history = MutableStateFlow(emptyList<HistoryEntry>())
        val updates = MutableStateFlow(emptyList<NewChapter>())
        val showSensitive = MutableStateFlow(false)

        val getInstalledSources = FakeGetInstalledSources(sources)

        fun viewModel(): HomeViewModel =
            HomeViewModel(
                observeLibraryReadingHistory = FakeObserveLibraryReadingHistory(history),
                observeNewChapters = FakeObserveNewChapters(updates),
                observeVisibleSources =
                    ObserveVisibleSources.Default(
                        getInstalledSources,
                        object : ObserveShowSensitiveContent {
                            override fun invoke(): Flow<Boolean> = showSensitive
                        }
                    ),
                dispatcher = ImmediateDispatchers,
                logger = SilentLogger
            )
    }
}

private fun source(
    id: String,
    lang: String,
    nsfw: Boolean = false
): SourceMeta = SourceMeta(id = id, name = Text(id), lang = Text(lang), nsfw = nsfw)

private class FakeObserveLibraryReadingHistory(
    private val history: Flow<List<HistoryEntry>>
) : ObserveLibraryReadingHistory {
    override fun invoke(limit: Int): Flow<List<HistoryEntry>> = history
}

private class FakeObserveNewChapters(
    private val updates: Flow<List<NewChapter>>
) : ObserveNewChapters {
    override fun invoke(): Flow<List<NewChapter>> = updates
}

/**
 * Emits only once the enumeration has produced something, as the real loader does, and counts how often
 * it is subscribed — which is how a test tells a genuine pipeline restart from a resubscribe inside the
 * sharing window.
 */
private class FakeGetInstalledSources(
    private val sources: Flow<List<SourceMeta>?>
) : GetInstalledSources {
    var subscriptions = 0
        private set

    override fun invoke(): Flow<List<SourceMeta>> = sources.filterNotNull().onStart { subscriptions++ }
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
