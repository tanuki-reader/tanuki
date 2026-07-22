package app.tanuki.features.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import app.tanuki.assets.designSystem.TanukiTheme
import app.tanuki.assets.designSystem.component.SectionHeader
import app.tanuki.assets.designSystem.component.Segment
import app.tanuki.assets.designSystem.component.TanukiEmptyState
import app.tanuki.assets.designSystem.component.TanukiLoadingIndicator
import app.tanuki.assets.designSystem.component.TanukiSegmentedControl
import app.tanuki.assets.designSystem.component.TanukiTopBar
import app.tanuki.core.navigation.Destination
import app.tanuki.core.navigation.NavEvent
import app.tanuki.features.home.composable.ContinueReadingRow
import app.tanuki.features.home.composable.LanguageSourceGroup
import app.tanuki.features.home.resources.Res
import app.tanuki.features.home.resources.home_continue_reading
import app.tanuki.features.home.resources.home_for_you_empty
import app.tanuki.features.home.resources.home_segment_for_you
import app.tanuki.features.home.resources.home_segment_sources
import app.tanuki.features.home.resources.home_sources_empty
import app.tanuki.features.home.resources.home_title
import app.tanuki.features.home.viewmodel.HomeState.Content
import app.tanuki.features.home.viewmodel.HomeState.Loading
import app.tanuki.features.home.viewmodel.HomeViewModel
import dev.zacsweers.metrox.viewmodel.metroViewModel
import org.jetbrains.compose.resources.stringResource

/** Home's two views. Which one is showing is pure UI state — it changes nothing in the data. */
private enum class HomeView { ForYou, Sources }

/**
 * Stateful entry point for the Home tab. Resolves [HomeViewModel] from Metro, collects its state, and
 * forwards navigation to [onNavigate] — resuming a chapter, opening a series, or opening a source's
 * browse leaf. Mirrors [app.tanuki.features.library.LibraryRoute]'s shape: the only place the feature
 * touches nav mechanics.
 */
@Composable
fun HomeRoute(
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = metroViewModel(),
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
    HomeScreen(modifier = modifier, viewModel = viewModel)
}

@Composable
private fun HomeScreen(
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val spacing = TanukiTheme.spacing
    var view by rememberSaveable { mutableStateOf(HomeView.ForYou) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        // Title-only bar: Home has nothing to search — its two views are a curated shortlist and a short
        // source list, and the segmented control below is the screen's only control.
        TanukiTopBar(
            title = stringResource(Res.string.home_title),
            searchActive = false,
            searchQuery = "",
            onSearchOpen = null,
            onSearchClose = {},
            onSearchQueryChange = {}
        )

        TanukiSegmentedControl(
            segments =
                listOf(
                    Segment(HomeView.ForYou, stringResource(Res.string.home_segment_for_you)),
                    Segment(HomeView.Sources, stringResource(Res.string.home_segment_sources))
                ),
            selected = view,
            onSelect = { view = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.gutter, vertical = spacing.sm)
        )

        when (val current = state) {
            Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { TanukiLoadingIndicator() }

            is Content -> when (view) {
                HomeView.ForYou -> ForYouView(current, viewModel)
                HomeView.Sources -> SourcesView(current, viewModel)
            }
        }
    }
}

/**
 * The For You view: the user's in-progress library titles. A "Recent Updates" section is intentionally
 * absent until real update tracking exists — the [LazyColumn] is here so it can slot in beside Continue
 * Reading without restructuring the screen.
 */
@Composable
private fun ForYouView(
    content: Content,
    viewModel: HomeViewModel
) {
    val spacing = TanukiTheme.spacing
    if (content.continueReading.isEmpty()) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            TanukiEmptyState(message = stringResource(Res.string.home_for_you_empty))
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.lg)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                SectionHeader(
                    title = stringResource(Res.string.home_continue_reading),
                    modifier = Modifier.padding(horizontal = spacing.gutter)
                )
                ContinueReadingRow(
                    items = content.continueReading,
                    onItemClick = viewModel::onContinueReadingClick
                )
            }
        }
    }
}

/** The Sources view: installed sources bucketed into collapsible language groups. */
@Composable
private fun SourcesView(
    content: Content,
    viewModel: HomeViewModel
) {
    val spacing = TanukiTheme.spacing
    if (content.sourceGroups.isEmpty()) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            TanukiEmptyState(message = stringResource(Res.string.home_sources_empty))
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = spacing.gutter, vertical = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.sm)
    ) {
        items(content.sourceGroups, key = { it.lang }) { group ->
            LanguageSourceGroup(
                lang = group.lang,
                expanded = group.expanded,
                sources = group.sources,
                onToggle = { viewModel.onToggleLanguage(group.lang) },
                onSourceClick = viewModel::onSourceClick
            )
        }
    }
}
