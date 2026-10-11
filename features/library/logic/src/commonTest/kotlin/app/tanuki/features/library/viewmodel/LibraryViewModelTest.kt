package app.tanuki.features.library.viewmodel

import app.tanuki.core.domain.category.model.Category
import app.tanuki.core.domain.category.usecase.ObserveCategories
import app.tanuki.core.domain.common.model.Text
import app.tanuki.core.domain.common.model.TriState
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
import app.tanuki.core.domain.settings.model.LibraryGridSize
import app.tanuki.core.domain.settings.usecase.ObserveLibraryGridSize
import app.tanuki.core.domain.library.model.LibraryPreferences
import app.tanuki.core.domain.library.usecase.ObserveLibraryPreferences
import app.tanuki.core.domain.library.usecase.UpdateLibraryPreferences
import app.tanuki.features.library.viewmodel.LibraryState.Content
import app.tanuki.features.library.viewmodel.LibraryState.Loading
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Covers the sheet, which is the part of this screen the `StateViewModel` migration actually rewrote:
 * it used to be written over the pipeline's own output, from handlers that read that output back and
 * `require`d its shape. It is now an input the pipeline is a function of.
 *
 * Every case collects `uiState` for the duration — the state is shared under `WhileSubscribed`, so an
 * uncollected ViewModel is deliberately inert and would report nothing but `Loading`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {
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
    fun `the grid is shown once the library and the categories have both emitted`() = runTest {
        val world = World()
        val viewModel = world.viewModel()

        val job = collect(viewModel)

        val state = viewModel.uiState.value
        assertTrue(state is Content)
        assertEquals(listOf("Berserk"), state.items.map { it.title })
        job.cancel()
    }

    @Test
    fun `nothing is shown until a collector arrives`() = runTest {
        val world = World()
        val viewModel = world.viewModel()

        runCurrent()

        assertEquals(Loading, viewModel.uiState.value)
    }

    @Test
    fun `the filter button opens the sheet carrying the current categories`() = runTest {
        val world = World()
        val viewModel = world.viewModel()
        val job = collect(viewModel)

        viewModel.onFilterClick()
        runCurrent()

        val state = viewModel.uiState.value
        assertTrue(state is Content && state.sheetOpen)
        assertEquals(listOf("Shelf"), state.categories.map { it.name })
        job.cancel()
    }

    @Test
    fun `opening the sheet clears the search so it acts on the whole library`() = runTest {
        val world = World()
        val viewModel = world.viewModel()
        val job = collect(viewModel)

        viewModel.onSearchQueryChange("ber")
        runCurrent()
        viewModel.onFilterClick()
        runCurrent()
        viewModel.toLibraryState()
        runCurrent()

        val state = viewModel.uiState.value
        assertTrue(state is Content)
        assertEquals("", state.searchQuery)
        job.cancel()
    }

    @Test
    fun `dismissing the sheet returns to the grid`() = runTest {
        val world = World()
        val viewModel = world.viewModel()
        val job = collect(viewModel)

        viewModel.onFilterClick()
        runCurrent()
        viewModel.toLibraryState()
        runCurrent()

        val state = viewModel.uiState.value
        assertTrue(state is Content)
        assertFalse(state.sheetOpen)
        job.cancel()
    }

    @Test
    fun `a category renamed while the sheet is open updates its chip`() = runTest {
        val world = World()
        val viewModel = world.viewModel()
        val job = collect(viewModel)
        viewModel.onFilterClick()
        runCurrent()

        world.categories.value = listOf(Category(id = SHELF_ID, name = Text("Reading")))
        runCurrent()

        val state = viewModel.uiState.value
        assertTrue(state is Content)
        assertEquals(listOf("Reading"), state.categories.map { it.name })
        job.cancel()
    }

    @Test
    fun `toggling a category marks its chip enabled`() = runTest {
        val world = World()
        val viewModel = world.viewModel()
        val job = collect(viewModel)
        viewModel.onFilterClick()
        runCurrent()

        viewModel.onCategoryToggle(SHELF_ID)
        runCurrent()

        val state = viewModel.uiState.value
        assertTrue(state is Content)
        assertEquals(listOf(true), state.categories.map { it.isEnabled })
        assertEquals(setOf(SHELF_ID), world.preferences.value.filter.categoryIds)
        job.cancel()
    }

    @Test
    fun `the remembered filter and sort apply when the screen opens`() = runTest {
        val world = World()
        val sort = LibrarySort(LibrarySort.Mode.DateAdded, LibrarySort.Direction.Descending)
        world.preferences.value = LibraryPreferences(LibraryFilter(readingStatus = ReadingStatus.DROPPED), sort)
        val viewModel = world.viewModel()

        val job = collect(viewModel)

        val state = viewModel.uiState.value
        assertTrue(state is Content)
        assertEquals(ReadingStatus.DROPPED, state.filter.readingStatus)
        assertEquals(sort, state.sort)
        assertTrue(state.filterActive)
        job.cancel()
    }

    @Test
    fun `a deleted category left in the stored filter is ignored`() = runTest {
        val world = World()
        world.preferences.value = LibraryPreferences(LibraryFilter(categoryIds = setOf(Uuid.random())))
        val viewModel = world.viewModel()

        val job = collect(viewModel)

        val state = viewModel.uiState.value
        assertTrue(state is Content)
        assertEquals(emptySet(), state.filter.categoryIds)
        assertFalse(state.filterActive)
        job.cancel()
    }

    @Test
    fun `the sheet reopens on the tab last used`() = runTest {
        val world = World()
        val viewModel = world.viewModel()
        val job = collect(viewModel)

        viewModel.onFilterClick()
        viewModel.onSheetTabChange(LibrarySheetTab.Filter)
        viewModel.toLibraryState()
        viewModel.onFilterClick()
        runCurrent()

        val state = viewModel.uiState.value
        assertTrue(state is Content)
        assertEquals(LibrarySheetTab.Filter, state.sheetTab)
        job.cancel()
    }

    @Test
    fun `a filter change shows before the store has written it`() = runTest {
        val world = World()
        world.storeIsSlow = true
        val viewModel = world.viewModel()
        val job = collect(viewModel)

        viewModel.onDownloadedChange(TriState.REQUIRED)
        runCurrent()

        val state = viewModel.uiState.value
        assertTrue(state is Content)
        assertEquals(TriState.REQUIRED, state.filter.downloaded)
        job.cancel()
    }

    @Test
    fun `a cover size changed in Appearance reaches the grid`() = runTest {
        val world = World()
        val viewModel = world.viewModel()
        val job = collect(viewModel)

        world.gridSize.value = LibraryGridSize.Large
        runCurrent()

        val state = viewModel.uiState.value
        assertTrue(state is Content)
        assertEquals(LibraryGridSize.Large, state.gridSize)
        job.cancel()
    }

    @Test
    fun `tapping the current sort flips it and another mode starts ascending`() = runTest {
        val world = World()
        val viewModel = world.viewModel()
        val job = collect(viewModel)

        viewModel.onSortModeClick(LibrarySort.Mode.Alphabetical)
        runCurrent()
        assertEquals(LibrarySort.Direction.Descending, world.preferences.value.sort.direction)

        viewModel.onSortModeClick(LibrarySort.Mode.LastRead)
        runCurrent()
        assertEquals(LibrarySort(LibrarySort.Mode.LastRead, LibrarySort.Direction.Ascending), world.preferences.value.sort)
        job.cancel()
    }

    /** Keeps `uiState` subscribed for the body of a test; the pipeline runs only while it is. */
    private fun TestScope.collect(viewModel: LibraryViewModel): Job {
        val job = launch { viewModel.uiState.collect {} }
        runCurrent()
        return job
    }

    private companion object {
        val SHELF_ID: Uuid = Uuid.random()
    }

    /** The fakes one test needs, built together so each case reads as its own scenario. */
    private class World {
        val library = MutableStateFlow(listOf(manga("Berserk")))
        val categories = MutableStateFlow(listOf(Category(id = SHELF_ID, name = Text("Shelf"))))
        val preferences = MutableStateFlow(LibraryPreferences())
        val gridSize = MutableStateFlow(LibraryGridSize.Medium)

        /** When set, writes never land: what the screen shows can only come from the ViewModel itself. */
        var storeIsSlow = false

        fun viewModel(): LibraryViewModel =
            LibraryViewModel(
                observeLibrary = FakeObserveLibrary(library),
                observeCategories = FakeObserveCategories(categories),
                observeLibraryPreferences = FakeLibraryPreferences(preferences),
                updateLibraryPreferences = FakeLibraryPreferences(preferences) { storeIsSlow },
                observeLibraryGridSize = FakeObserveLibraryGridSize(gridSize),
                dispatcher = ImmediateDispatchers,
                logger = SilentLogger
            )
    }
}

/** Answers with whatever the backing flow holds; the filtering itself belongs to `ObserveLibrary`. */
private class FakeObserveLibrary(
    private val library: Flow<List<LibraryManga>>
) : ObserveLibrary {
    override fun invoke(
        searchQuery: Flow<String>,
        sort: Flow<LibrarySort>,
        filter: Flow<LibraryFilter>
    ): Flow<List<LibraryManga>> = library
}

private class FakeObserveCategories(
    private val categories: Flow<List<Category>>
) : ObserveCategories {
    override fun invoke(): Flow<List<Category>> = categories
}

private class FakeObserveLibraryGridSize(
    private val size: Flow<LibraryGridSize>
) : ObserveLibraryGridSize {
    override fun invoke(): Flow<LibraryGridSize> = size
}

/** The preferences store as one in-memory value, read and written by both use cases. */
private class FakeLibraryPreferences(
    private val preferences: MutableStateFlow<LibraryPreferences>,
    private val slow: () -> Boolean = { false }
) : ObserveLibraryPreferences,
    UpdateLibraryPreferences {
    override fun invoke(): Flow<LibraryPreferences> = preferences

    override suspend fun invoke(transform: (LibraryPreferences) -> LibraryPreferences) {
        if (slow()) awaitCancellation()
        preferences.value = transform(preferences.value)
    }
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

private fun manga(
    title: String,
    id: Uuid = Uuid.random()
): LibraryManga =
    LibraryManga(
        id = id,
        title = Text(title),
        cover = MangaCover(mangaId = id, url = null),
        unreadCount = 0u,
        isDownloaded = false,
        dateAdded = Instant.DISTANT_PAST,
        lastRead = null,
        readingStatus = ReadingStatus.READING,
        publishStatus = PublishStatus.ONGOING
    )
