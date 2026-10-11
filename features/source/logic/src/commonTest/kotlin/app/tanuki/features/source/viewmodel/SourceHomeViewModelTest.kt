package app.tanuki.features.source.viewmodel

import app.tanuki.core.domain.chapter.model.ChapterNumber
import app.tanuki.core.domain.common.model.Text
import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.common.service.LogPriority
import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.domain.manga.usecase.CacheSourceManga
import app.tanuki.core.domain.source.model.MangaListing
import app.tanuki.core.domain.source.model.MangaPage
import app.tanuki.core.domain.source.model.SourceListing
import app.tanuki.core.domain.source.model.SourceListing.LATEST
import app.tanuki.core.domain.source.model.SourceListing.POPULAR
import app.tanuki.core.domain.source.model.SourceMeta
import app.tanuki.core.domain.source.usecase.BrowseSource
import app.tanuki.core.domain.source.usecase.GetSourceHome
import app.tanuki.core.domain.source.usecase.GetSourceMeta
import app.tanuki.core.domain.source.usecase.ObserveLibraryKeys
import app.tanuki.core.domain.source.usecase.SourceHomeRail
import app.tanuki.core.navigation.NavEvent
import app.tanuki.core.navigation.SourceCatalogGrid
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class SourceHomeViewModelTest {
    private val main = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(main)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `rails follow the declared listings then the default catalog`() = runTest {
        val vm = World().viewModel()
        val job = collect(vm)

        val state = assertIs<SourceHomeState.Content>(vm.uiState.value)
        assertEquals(listOf(POPULAR, LATEST, null), state.rails.map { it.listing })
        assertEquals("Source", state.title)
        assertEquals(2, state.listingCount)
        job.cancel()
    }

    @Test
    fun `items carry the in-library flag and preformatted chapter info`() = runTest {
        val world = World()
        world.libraryKeys.value = setOf("a")
        val vm = world.viewModel()
        val job = collect(vm)

        val items = assertIs<SourceHomeState.Content>(vm.uiState.value).rails.first().items
        assertEquals(listOf(true, false), items.map { it.inLibrary })
        assertEquals("12.5", items.first().latestChapterLabel)
        assertEquals("Action · Drama", items.first().subtitle)
        assertEquals("Someone", items.last().subtitle)
        job.cancel()
    }

    @Test
    fun `a failed rail can be retried on its own`() = runTest {
        val world = World(failing = LATEST)
        val vm = world.viewModel()
        val job = collect(vm)
        val rail = { assertIs<SourceHomeState.Content>(vm.uiState.value).rails.single { it.listing == LATEST } }
        assertEquals(RailStatus.Failed, rail().status)

        world.failing = null
        vm.onRetryRail(LATEST)
        runCurrent()

        assertEquals(RailStatus.Loaded, rail().status)
        job.cancel()
    }

    @Test
    fun `see all and a submitted search open the catalog`() = runTest {
        val vm = World().viewModel()
        val job = collect(vm)
        val events = mutableListOf<NavEvent>()
        val nav = launch { vm.navEvents.collect { events += it } }

        vm.onSeeAll(LATEST)
        vm.onSearchQueryChange("  solo  ")
        vm.onSearchSubmit()
        runCurrent()

        assertEquals(
            listOf(SourceCatalogGrid("src", listing = "latest"), SourceCatalogGrid("src", query = "solo")),
            events.map { (it as NavEvent.NavTo).destination }
        )
        nav.cancel()
        job.cancel()
    }

    @Test
    fun `an unknown source ends in the error state`() = runTest {
        val vm = World(meta = null).viewModel()
        val job = collect(vm)

        assertEquals(SourceHomeState.Error, vm.uiState.value)
        job.cancel()
    }

    private fun TestScope.collect(vm: SourceHomeViewModel): Job {
        val job = launch { vm.uiState.collect {} }
        runCurrent()
        return job
    }

    private class World(
        val meta: SourceMeta? = SourceMeta("src", Text("Source"), Text("en"), listings = listOf(POPULAR, LATEST)),
        var failing: SourceListing? = null
    ) {
        val libraryKeys = MutableStateFlow(emptySet<String>())

        fun page(listing: SourceListing?): List<MangaListing> =
            listOf(
                MangaListing(
                    "a",
                    title = "A ${listing?.wireName}",
                    genres = listOf("Action", "Drama"),
                    latestChapter = ChapterNumber(12.5),
                    updatedAt = Clock.System.now() - 3.hours
                ),
                MangaListing("b", title = "B", author = "Someone")
            )

        fun viewModel(): SourceHomeViewModel {
            val browse =
                object : BrowseSource {
                    override suspend fun invoke(sourceId: String, listing: SourceListing?, query: String?, page: Int): MangaPage {
                        if (listing == failing && listing != null) error("boom")
                        return MangaPage(page(listing), hasNextPage = false)
                    }
                }
            return SourceHomeViewModel(
                sourceId = "src",
                getSourceMeta = object : GetSourceMeta {
                    override suspend fun invoke(sourceId: String): SourceMeta? = meta
                },
                getSourceHome = object : GetSourceHome {
                    override suspend fun invoke(sourceId: String, listings: List<SourceListing>): List<SourceHomeRail> =
                        (listings + null).map { l ->
                            SourceHomeRail(l, runCatching { browse(sourceId, l).entries }.getOrNull())
                        }
                },
                browseSource = browse,
                cacheSourceManga = object : CacheSourceManga {
                    override suspend fun invoke(sourceId: String, listing: MangaListing): Uuid = Uuid.random()
                },
                observeLibraryKeys = object : ObserveLibraryKeys {
                    override fun invoke(sourceId: String): Flow<Set<String>> = libraryKeys
                },
                dispatcher = MainDispatchers,
                logger = SilentLogger
            )
        }
    }
}

private object MainDispatchers : DispatcherProvider {
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
