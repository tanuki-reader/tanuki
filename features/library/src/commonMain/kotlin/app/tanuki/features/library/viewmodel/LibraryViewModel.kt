package app.tanuki.features.library.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tanuki.core.domain.category.model.Category
import app.tanuki.core.domain.category.usecase.ObserveCategories
import app.tanuki.core.domain.common.model.TriState
import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.domain.library.model.CategoryMatchMode
import app.tanuki.core.domain.library.model.LibraryFilter
import app.tanuki.core.domain.library.model.LibrarySort
import app.tanuki.core.domain.library.usecase.ObserveLibrary
import app.tanuki.core.domain.manga.model.ReadingStatus
import app.tanuki.core.navigation.MangaDetail
import app.tanuki.core.presentation.TanukiViewModel
import app.tanuki.features.library.viewmodel.LibraryState.DisplayLibrary
import app.tanuki.features.library.viewmodel.LibraryState.Loading
import app.tanuki.features.library.viewmodel.LibraryState.ShowingBottomSheet
import app.tanuki.features.library.viewmodel.LibraryState.ShowingBottomSheet.CategoryChip
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlin.uuid.Uuid

/**
 * Presentation logic for the Library tab. Compose-free (per CLAUDE.md → MVVM): it exposes state as a
 * [StateFlow] and screen-scoped navigation through [TanukiViewModel.navEvents], so the same logic drives
 * the shared Compose UI on both Android and iOS and is unit-testable without a Compose runtime.
 *
 * Metro-injected and registered into the app's ViewModel multibinding via [ViewModelKey] +
 * [ContributesIntoMap], so `metroViewModel<LibraryViewModel>()` resolves it in the UI layer.
 *
 * The pipeline is already reactive: [searchQuery], [filter], and [sort] flow into [ObserveLibrary], which
 * re-derives the list in memory on any change. Sort/filter hold their defaults for now — wiring
 * them to user controls is a later slice that needs no change here, only emissions into those flows.
 */
@Inject
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey
class LibraryViewModel(
    observeLibrary: ObserveLibrary,
    observeCategories: ObserveCategories,
    dispatcher: DispatcherProvider,
    logger: Logger
) : TanukiViewModel(dispatcher, logger) {
    val uiState: StateFlow<LibraryState>
        field = MutableStateFlow<LibraryState>(Loading)

    /**
     * The live search query. Fed into [ObserveLibrary] below, which re-derives the list whenever the library
     * or the query changes. Being a [StateFlow], rapid keystrokes are conflated to the latest value — no
     * stale, out-of-order results the way per-keystroke coroutines could produce.
     */
    private val searchQuery = MutableStateFlow("")
    private val filter = MutableStateFlow(LibraryFilter.None)
    private val sort = MutableStateFlow(LibrarySort.Default)

    /**
     * The most recent library categories (each carrying its member manga ids). Cached from the pipeline
     * below so [onFilterClick] can seed the sheet immediately, rather than opening with an empty category
     * list until the next emission.
     */
    private var latestCategories: List<CategoryChip> = emptyList()

    init {
        combine(
            observeLibrary(searchQuery = searchQuery, sort = sort, filter = filter),
            observeCategories()
        ) { library, categories ->
            val selectedIds = filter.value.categories.mapTo(mutableSetOf()) { it.id }
            latestCategories = categories.map { it.toCategoryChip(selectedIds) }
            when (uiState.value) {
                is Loading, is DisplayLibrary -> {
                    DisplayLibrary(library, filter.value, sort.value, searchQuery.value)
                }

                is ShowingBottomSheet -> {
                    ShowingBottomSheet(library, filter.value, sort.value, latestCategories)
                }
            }
        }.flowOn(dispatcher.default)
            .onEach { uiState.value = it }
            .launchIn(viewModelScope)
    }

    /** A library cover was tapped — request navigation to that manga's detail screen. */
    fun onMangaClick(mangaId: Uuid) {
        navigateTo(MangaDetail(mangaId))
    }

    /** The search field changed — publish the query; [ObserveLibrary] above re-derives the filtered state. */
    fun onSearchQueryChange(query: String) {
        searchQuery.value = query
    }

    fun toLibraryState() {
        val curr = uiState.value
        if (curr is Loading || curr is DisplayLibrary) return
        require(curr is ShowingBottomSheet)
        uiState.value =
            DisplayLibrary(
                items = curr.items,
                filter = curr.filter,
                sort = curr.sort,
                searchQuery = ""
            )
    }

    /**
     * The filter button was tapped — open the filter & sort sheet ([LibraryState.ShowingBottomSheet])
     * over the grid. Search is cleared so the sheet acts on the whole library; the sheet's controls feed
     * [onSortChange]/[onReadingStatusChange]/[onPublicationCompletedChange], which re-derive the list.
     */
    fun onFilterClick() {
        val curr = uiState.value
        if (curr is Loading || curr is ShowingBottomSheet) return
        require(curr is DisplayLibrary)
        uiState.value =
            ShowingBottomSheet(
                items = curr.items,
                filter = curr.filter,
                sort = curr.sort,
                categories = latestCategories
            )
    }

    /**
     * Change the library ordering from the sheet. Publishing into [sort] re-derives the list in
     * [ObserveLibrary]; the resulting emission rebuilds [ShowingBottomSheet] with the new [LibrarySort].
     */
    fun onSortChange(sort: LibrarySort) {
        this.sort.value = sort
    }

    /**
     * Set the reading-status filter (or `null` to clear it) from the sheet. Feeds [filter], which
     * re-filters the list and rebuilds the sheet state via the [ObserveLibrary] pipeline.
     */
    fun onReadingStatusChange(status: ReadingStatus?) {
        filter.value = filter.value.copy(readingStatus = status)
    }

    /** Set the publication-status tri-state filter from the sheet. */
    fun onPublicationCompletedChange(triState: TriState) {
        filter.value = filter.value.copy(publicationCompleted = triState)
    }

    /**
     * Gate the library on whether a series has anything on the device.
     *
     * `EXCLUDED` is the arm worth having: "show me what I have *not* kept" is how a user finds the
     * series to download before a flight, and no plain on/off toggle can express it.
     */
    fun onDownloadedChange(triState: TriState) {
        filter.value = filter.value.copy(downloaded = triState)
    }

    /**
     * Toggle a category chip. Membership is keyed by [Category.id] (not structural equality), so a stored
     * selection survives an unrelated change to the category's member set; [ObserveLibrary] then re-filters
     * using [LibraryFilter.categoryMatchMode].
     */
    fun onCategoryToggle(category: Category) {
        val current = filter.value.categories
        val next =
            if (current.any { it.id == category.id }) {
                current.filterNot { it.id == category.id }.toSet()
            } else {
                current + category
            }
        filter.value = filter.value.copy(categories = next)
    }

    /** Switch how selected categories combine — match ANY (OR) vs. ALL (AND). */
    fun onCategoryMatchModeChange(mode: CategoryMatchMode) {
        filter.value = filter.value.copy(categoryMatchMode = mode)
    }

    /** Clear every filter and restore the default ordering from the sheet's reset control. */
    fun onResetFilters() {
        filter.value = LibraryFilter.None
        sort.value = LibrarySort.Default
    }
}