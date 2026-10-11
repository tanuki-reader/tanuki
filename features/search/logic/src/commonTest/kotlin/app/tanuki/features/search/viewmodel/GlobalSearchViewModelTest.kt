package app.tanuki.features.search.viewmodel

import app.tanuki.core.domain.common.model.Text
import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.common.service.LogPriority
import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.domain.library.model.LibraryFilter
import app.tanuki.core.domain.library.model.LibraryManga
import app.tanuki.core.domain.library.model.LibrarySort
import app.tanuki.core.domain.library.usecase.ObserveLibrary
import app.tanuki.core.domain.manga.model.MangaCover
import app.tanuki.core.domain.manga.model.PublishStatus
import app.tanuki.core.domain.manga.model.ReadingStatus
import app.tanuki.core.domain.manga.usecase.CacheSourceManga
import app.tanuki.core.domain.settings.usecase.ObserveShowSensitiveContent
import app.tanuki.core.domain.source.model.MangaListing
import app.tanuki.core.domain.source.model.SourceMeta
import app.tanuki.core.domain.source.usecase.GetInstalledSources
import app.tanuki.core.domain.source.usecase.ObserveLibraryKeys
import app.tanuki.core.domain.source.usecase.SearchSources
import app.tanuki.core.domain.source.usecase.SourceSearch
import app.tanuki.core.navigation.MangaDetail
import app.tanuki.core.navigation.NavEvent
import app.tanuki.core.navigation.SourceCatalogGrid
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class GlobalSearchViewModelTest {
    private val main = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(main)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `typing filters the library without searching the sources`() = runTest {
        val world = World()
        val vm = world.viewModel()
        val job = collect(vm)

        vm.onQueryChange("one")
        advanceUntilIdle()

        assertEquals(listOf("One Piece"), vm.uiState.value.library.map { it.title })
        assertEquals(emptyList(), vm.uiState.value.rails)
        assertEquals(emptyList(), world.searches)
        job.cancel()
    }

    @Test
    fun `submitting searches every shown source - adult-only ones only when allowed`() = runTest {
        val world = World()
        val vm = world.viewModel()
        val job = collect(vm)

        vm.onQueryChange("  piece ")
        vm.onSubmit()
        advanceUntilIdle()

        assertEquals(listOf("piece" to listOf("md", "broken")), world.searches)
        val rails = vm.uiState.value.rails
        assertEquals(listOf("md", "broken"), rails.map { it.sourceId })
        assertEquals(listOf(SearchStatus.Loaded, SearchStatus.Failed), rails.map { it.status })
        assertEquals(listOf(false, true), rails.first().items.map { it.inLibrary })
        job.cancel()
    }

    @Test
    fun `an initial query searches at once and retry re-runs one rail`() = runTest {
        val world = World()
        val vm = world.viewModel(initialQuery = "piece")
        val job = collect(vm)
        advanceUntilIdle()
        world.broken = false

        vm.onRetry("broken")
        advanceUntilIdle()

        assertEquals(listOf("piece" to listOf("md", "broken"), "piece" to listOf("broken")), world.searches)
        assertEquals(SearchStatus.Loaded, vm.uiState.value.rails.last().status)
        job.cancel()
    }

    @Test
    fun `results survive the screen going away and coming back`() = runTest {
        val world = World()
        val vm = world.viewModel(initialQuery = "piece")
        collect(vm).also { advanceUntilIdle() }.cancel()
        advanceUntilIdle()

        val job = collect(vm)
        advanceUntilIdle()

        assertEquals(1, world.searches.size)
        assertTrue(vm.uiState.value.rails.isNotEmpty())
        job.cancel()
    }

    @Test
    fun `a result opens the series and See all opens the source's catalog`() = runTest {
        val world = World()
        val vm = world.viewModel(initialQuery = "piece")
        val job = collect(vm)
        val events = mutableListOf<NavEvent>()
        val nav = launch { vm.navEvents.collect { events += it } }
        advanceUntilIdle()

        vm.onResultClick("md", "md-a")
        vm.onSeeAll("md")
        advanceUntilIdle()

        assertEquals(listOf("md" to "md-a"), world.cached)
        assertEquals(
            listOf(MangaDetail(world.cachedId), SourceCatalogGrid("md", query = "piece")).toSet(),
            events.map { (it as NavEvent.NavTo).destination }.toSet()
        )
        nav.cancel()
        job.cancel()
    }

    private fun TestScope.collect(vm: GlobalSearchViewModel): Job {
        val job = launch { vm.uiState.collect {} }
        runCurrent()
        return job
    }

    private class World {
        val searches = mutableListOf<Pair<String, List<String>>>()
        val cached = mutableListOf<Pair<String, String>>()
        val cachedId = Uuid.random()
        var broken = true
        private val libraryId = Uuid.random()

        fun viewModel(initialQuery: String? = null) =
            GlobalSearchViewModel(
                initialQuery = initialQuery,
                getInstalledSources =
                    object : GetInstalledSources {
                        override fun invoke(): Flow<List<SourceMeta>> =
                            flowOf(
                                listOf(
                                    SourceMeta("md", Text("MangaDex"), Text("en")),
                                    SourceMeta("adult", Text("Adult"), Text("en"), nsfw = true),
                                    SourceMeta("broken", Text("Broken"), Text("pt-BR"))
                                )
                            )
                    },
                observeShowSensitiveContent =
                    object : ObserveShowSensitiveContent {
                        override fun invoke(): Flow<Boolean> = flowOf(false)
                    },
                searchSources =
                    object : SearchSources {
                        override fun invoke(
                            query: String,
                            sourceIds: List<String>
                        ): Flow<Pair<String, SourceSearch>> =
                            flow {
                                searches += query to sourceIds
                                sourceIds.forEach { id ->
                                    emit(
                                        id to
                                            if (id == "broken" && broken) {
                                                SourceSearch.Failed
                                            } else {
                                                SourceSearch.Loaded(listOf(MangaListing("$id-a", title = "A"), MangaListing("$id-b", title = "B")))
                                            }
                                    )
                                }
                            }
                    },
                cacheSourceManga =
                    object : CacheSourceManga {
                        override suspend fun invoke(
                            sourceId: String,
                            listing: MangaListing
                        ): Uuid {
                            cached += sourceId to listing.sourceMangaKey
                            return cachedId
                        }
                    },
                observeLibrary =
                    object : ObserveLibrary {
                        override fun invoke(
                            searchQuery: Flow<String>,
                            sort: Flow<LibrarySort>,
                            filter: Flow<LibraryFilter>
                        ): Flow<List<LibraryManga>> =
                            combine(searchQuery, flowOf(listOf(libraryManga("One Piece"), libraryManga("Solo Leveling")))) { q, all ->
                                all.filter { it.title.value.contains(q.trim(), ignoreCase = true) }
                            }
                    },
                observeLibraryKeys =
                    object : ObserveLibraryKeys {
                        override fun invoke(sourceId: String): Flow<Set<String>> = MutableStateFlow(setOf("$sourceId-b"))
                    },
                dispatcher = MainDispatchers,
                logger = SilentLogger
            )

        private fun libraryManga(title: String) =
            LibraryManga(
                id = libraryId,
                title = Text(title),
                cover = MangaCover(libraryId, null),
                unreadCount = 0u,
                isDownloaded = false,
                dateAdded = Clock.System.now(),
                lastRead = null,
                readingStatus = ReadingStatus.READING,
                publishStatus = PublishStatus.ONGOING
            )
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
