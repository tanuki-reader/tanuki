package app.tanuki.features.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tanuki.assets.designSystem.component.TanukiLoadingIndicator
import app.tanuki.assets.designSystem.component.TanukiTopBar
import app.tanuki.core.navigation.Destination
import app.tanuki.core.navigation.NavEvent
import app.tanuki.features.library.composable.EmptyView
import app.tanuki.features.library.composable.LibraryView
import app.tanuki.features.library.composable.components.LibraryBottomSheet
import app.tanuki.features.library.resources.Res
import app.tanuki.features.library.resources.library_entry_count
import app.tanuki.features.library.resources.library_filter_empty
import app.tanuki.features.library.resources.library_search_empty
import app.tanuki.features.library.resources.library_search_hint
import app.tanuki.features.library.resources.library_title
import app.tanuki.features.library.viewmodel.LibraryEmpty
import app.tanuki.features.library.viewmodel.LibraryState.Content
import app.tanuki.features.library.viewmodel.LibraryState.Loading
import app.tanuki.features.library.viewmodel.LibraryViewModel
import dev.zacsweers.metrox.viewmodel.metroViewModel
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Stateful entry point for the Library tab: resolves the [LibraryViewModel] from Metro, collects its
 * state, and forwards its screen-scoped [NavEvent]s to [onNavigate] so the host performs the actual push.
 * This is the only place the feature touches navigation mechanics — the composable below stays pure UI.
 *
 * @param modifier the overall modifier for the composable.
 * @param viewModel the ViewModel to drive the UI.
 * @param onNavigate invoked with the [Destination] a [NavEvent.NavTo] targets (e.g., a manga's detail).
 */
@Composable
fun LibraryRoute(
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = metroViewModel(),
    onNavigate: (Destination) -> Unit
) {
    LaunchedEffect(viewModel) {
        viewModel.navEvents.collect { event ->
            when (event) {
                is NavEvent.NavTo -> onNavigate(event.destination)
                NavEvent.NavBack -> Unit
            }
        }
    }

    LibraryScreen(
        modifier = modifier,
        viewModel = viewModel
    )
}

@Composable
private fun LibraryScreen(
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var searchActive by rememberSaveable { mutableStateOf(false) }
    val content = state as? Content

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .statusBarsPadding()
    ) {
        TanukiTopBar(
            title = stringResource(Res.string.library_title),
            searchActive = searchActive,
            searchQuery = content?.searchQuery.orEmpty(),
            onSearchOpen = { searchActive = true },
            onSearchClose = {
                searchActive = false
                viewModel.onSearchQueryChange("")
            },
            onSearchQueryChange = viewModel::onSearchQueryChange,
            searchPlaceholder = stringResource(Res.string.library_search_hint),
            onFilterClick = viewModel::onFilterClick,
            filterActive = content?.filterActive == true
        )

        when (val state = state) {
            Loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    TanukiLoadingIndicator()
                }
            }

            is Content if state.empty == LibraryEmpty.Library -> {
                EmptyView(Modifier.fillMaxSize())
            }

            is Content -> {
                // While the sheet is open the grid stays beneath it and re-derives live as its controls
                // change.
                LibraryView(
                    items = state.items,
                    gridSize = state.gridSize,
                    countLabel =
                        pluralStringResource(
                            Res.plurals.library_entry_count,
                            state.items.size,
                            state.items.size
                        ),
                    emptyMessage =
                        if (state.empty == LibraryEmpty.Search) {
                            stringResource(Res.string.library_search_empty, state.searchQuery)
                        } else {
                            stringResource(Res.string.library_filter_empty)
                        },
                    onMangaClick = viewModel::onMangaClick,
                    modifier = Modifier.weight(1f)
                )
                if (state.sheetOpen) {
                    LibraryBottomSheet(
                        filter = state.filter,
                        sort = state.sort,
                        categories = state.categories,
                        itemCount = state.items.size,
                        tab = state.sheetTab,
                        onTabChange = viewModel::onSheetTabChange,
                        onDismiss = viewModel::toLibraryState,
                        onSortModeClick = viewModel::onSortModeClick,
                        onReadingStatusChange = viewModel::onReadingStatusChange,
                        onPublicationChange = viewModel::onPublicationCompletedChange,
                        onDownloadedChange = viewModel::onDownloadedChange,
                        onCategoryToggle = viewModel::onCategoryToggle,
                        onCategoryMatchModeChange = viewModel::onCategoryMatchModeChange,
                        onReset = viewModel::onResetFilters
                    )
                }
            }
        }
    }
}