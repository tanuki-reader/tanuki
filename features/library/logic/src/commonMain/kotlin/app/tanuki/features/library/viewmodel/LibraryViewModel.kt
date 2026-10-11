package app.tanuki.features.library.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tanuki.core.domain.category.usecase.ObserveCategories
import app.tanuki.core.domain.common.model.TriState
import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.domain.library.model.CategoryMatchMode
import app.tanuki.core.domain.library.model.LibraryFilter
import app.tanuki.core.domain.library.model.LibraryPreferences
import app.tanuki.core.domain.library.model.LibrarySort
import app.tanuki.core.domain.library.model.LibrarySort.Direction.Ascending
import app.tanuki.core.domain.library.model.LibrarySort.Direction.Descending
import app.tanuki.core.domain.library.usecase.ObserveLibrary
import app.tanuki.core.domain.library.usecase.ObserveLibraryPreferences
import app.tanuki.core.domain.library.usecase.UpdateLibraryPreferences
import app.tanuki.core.domain.manga.model.ReadingStatus
import app.tanuki.core.domain.settings.usecase.ObserveLibraryGridSize
import app.tanuki.core.navigation.MangaDetail
import app.tanuki.core.presentation.StateViewModel
import app.tanuki.features.library.viewmodel.LibraryState.Content
import app.tanuki.features.library.viewmodel.LibraryState.Loading
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

/**
 * Presentation logic for the Library tab, shared by the Compose screen (Android) and the SwiftUI one
 * (iOS).
 *
 * The filter and sort are the user's remembered [LibraryPreferences]: every sheet control writes
 * through [UpdateLibraryPreferences], and the screen reads them back from the store, so they survive a
 * restart. Search and whether the sheet is open are this screen's own, in-memory inputs.
 */
@Inject
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey
class LibraryViewModel(
    observeLibrary: ObserveLibrary,
    observeCategories: ObserveCategories,
    observeLibraryPreferences: ObserveLibraryPreferences,
    private val updateLibraryPreferences: UpdateLibraryPreferences,
    observeLibraryGridSize: ObserveLibraryGridSize,
    dispatcher: DispatcherProvider,
    logger: Logger
) : StateViewModel<LibraryState>(dispatcher, logger, Loading) {
    /** The live search query. A [MutableStateFlow], so rapid keystrokes conflate to the latest. */
    private val searchQuery = MutableStateFlow("")

    /** Whether the filter & sort sheet is over the grid. */
    private val sheetOpen = MutableStateFlow(false)

    /** The sheet's open pane; kept across openings, so the sheet comes back where the user left it. */
    private val sheetTab = MutableStateFlow(LibrarySheetTab.Sort)

    /**
     * The user's latest choice, applied at once while the store catches up. Without it the screen would
     * show the old value until DataStore has written the file and re-emitted, and a segmented control
     * bound to state snaps back for those frames (the flicker). Only this screen writes these settings,
     * so once set it always equals what the store will hold.
     */
    private val chosen = MutableStateFlow<LibraryPreferences?>(null)

    /**
     * The stored preferences (or the pending [chosen] ones) with category ids that no longer exist dropped. A deleted category left in
     * the filter would match nothing and empty the whole library, with no chip left to untick.
     */
    private val preferences: Flow<LibraryPreferences> =
        combine(observeLibraryPreferences(), chosen, observeCategories()) { stored, chosen, categories ->
            val current = chosen ?: stored
            val existing = categories.mapTo(mutableSetOf()) { it.id }
            current.copy(filter = current.filter.copy(categoryIds = current.filter.categoryIds intersect existing))
        }

    override val state: Flow<LibraryState> =
        combine(
            observeLibrary(
                searchQuery = searchQuery,
                sort = preferences.map { it.sort },
                filter = preferences.map { it.filter }
            ),
            observeCategories(),
            preferences,
            combine(searchQuery, sheetOpen, sheetTab, ::Knobs),
            observeLibraryGridSize()
        ) { library, categories, preferences, knobs, gridSize ->
            Content(
                items = library.map { it.toItem() },
                searchQuery = knobs.query,
                filter = preferences.filter,
                sort = preferences.sort,
                categories = categories.map { it.toChip(preferences.filter.categoryIds) },
                sheetOpen = knobs.sheetOpen,
                sheetTab = knobs.sheetTab,
                gridSize = gridSize
            )
        }

    /** A library cover was tapped — open that manga's detail screen. */
    fun onMangaClick(mangaId: Uuid) {
        navigateTo(MangaDetail(mangaId))
    }

    fun onSearchQueryChange(query: String) {
        searchQuery.value = query
    }

    /**
     * The filter button was tapped — open the sheet. Search is cleared so the sheet acts on, and its
     * "Show N" counts, the whole library.
     */
    fun onFilterClick() {
        searchQuery.value = ""
        sheetOpen.value = true
    }

    /** The sheet was dismissed. */
    fun toLibraryState() {
        sheetOpen.value = false
    }

    /** The sheet's Sort / Filter control was switched. */
    fun onSheetTabChange(tab: LibrarySheetTab) {
        sheetTab.value = tab
    }

    /** A sort row was tapped: a new mode starts ascending; the current one flips direction. */
    fun onSortModeClick(mode: LibrarySort.Mode) =
        update { prefs ->
            val sort = prefs.sort
            prefs.copy(
                sort =
                    if (sort.mode == mode) {
                        sort.copy(direction = if (sort.direction == Ascending) Descending else Ascending)
                    } else {
                        LibrarySort(mode, Ascending)
                    }
            )
        }

    /** Set the reading-status filter, or `null` to clear it. */
    fun onReadingStatusChange(status: ReadingStatus?) = updateFilter { it.copy(readingStatus = status) }

    /** REQUIRED keeps only finished series, EXCLUDED only ongoing ones. */
    fun onPublicationCompletedChange(triState: TriState) = updateFilter { it.copy(publicationCompleted = triState) }

    /**
     * Gate the library on whether a series has anything on the device. `EXCLUDED` is the arm worth
     * having: "what have I *not* kept" is how a user finds what to download before a flight.
     */
    fun onDownloadedChange(triState: TriState) = updateFilter { it.copy(downloaded = triState) }

    /** Add or remove a category from the filter. */
    fun onCategoryToggle(categoryId: Uuid) =
        updateFilter { filter ->
            val ids = filter.categoryIds
            filter.copy(categoryIds = if (categoryId in ids) ids - categoryId else ids + categoryId)
        }

    /** Switch how selected categories combine — match ANY (OR) vs. ALL (AND). */
    fun onCategoryMatchModeChange(mode: CategoryMatchMode) = updateFilter { it.copy(categoryMatchMode = mode) }

    /** Clear every filter and restore the default ordering. */
    fun onResetFilters() = update { LibraryPreferences() }

    private fun updateFilter(transform: (LibraryFilter) -> LibraryFilter) =
        update { it.copy(filter = transform(it.filter)) }

    /** Shows the change now (see [chosen]) and saves it; the store applies the same [transform]. */
    private fun update(transform: (LibraryPreferences) -> LibraryPreferences) {
        val shown = (uiState.value as? Content)?.let { LibraryPreferences(it.filter, it.sort) }
        if (shown != null) chosen.value = transform(chosen.value ?: shown)
        viewModelScope.launch { tryIo("Couldn't save the library filter") { updateLibraryPreferences(transform) } }
    }

    /** This screen's own inputs, sampled together as one combine arm. */
    private data class Knobs(
        val query: String,
        val sheetOpen: Boolean,
        val sheetTab: LibrarySheetTab
    )
}
