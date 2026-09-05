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
import androidx.compose.foundation.lazy.itemsIndexed
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
import app.tanuki.assets.designSystem.component.TanukiRowDivider
import app.tanuki.assets.designSystem.component.TanukiSectionGroup
import app.tanuki.assets.designSystem.component.TanukiSegmentedControl
import app.tanuki.assets.designSystem.component.TanukiTopBar
import app.tanuki.core.navigation.Destination
import app.tanuki.core.navigation.NavEvent
import app.tanuki.features.home.composable.ContinueReadingRow
import app.tanuki.features.home.composable.LanguageSourceGroup
import app.tanuki.features.home.composable.NewChapterRow
import app.tanuki.features.home.composable.NewChapterRowDividerInset
import app.tanuki.features.home.resources.Res
import app.tanuki.features.home.resources.home_continue_reading
import app.tanuki.features.home.resources.home_for_you_empty
import app.tanuki.features.home.resources.home_new_chapters
import app.tanuki.features.home.resources.home_segment_for_you
import app.tanuki.features.home.resources.home_segment_sources
import app.tanuki.features.home.resources.home_sources_empty
import app.tanuki.features.home.resources.home_title
import app.tanuki.features.home.viewmodel.HomeState.Content
import app.tanuki.features.home.viewmodel.HomeState.Loading
import app.tanuki.features.home.viewmodel.HomeViewModel
import dev.zacsweers.metrox.viewmodel.metroViewModel
import org.jetbrains.compose.resources.stringResource

/** Home's two views. Which one is showing is a pure UI state — it changes nothing in the data. */
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
    var view by rememberSaveable { mutableStateOf(HomeView.ForYou) }

    Column(
        modifier =
            modifier
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
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(TanukiTheme.spacing.gutter)
        )

        when (val current = state) {
            Loading -> {
                Box(Modifier.fillMaxSize(), Alignment.Center) { TanukiLoadingIndicator() }
            }

            is Content -> {
                when (view) {
                    HomeView.ForYou -> ForYouView(current, viewModel)
                    HomeView.Sources -> SourcesView(current, viewModel)
                }
            }
        }
    }
}

/**
 * The For You view: what the user is in the middle of, then what has arrived since.
 *
 * Continue Reading and the New Chapters header stay put; only the update list scrolls. Paging through a
 * long backlog never pushes the shelf the user was last reading from off the top of the screen.
 *
 * Each section renders only when it has something — a library with no updates shows just Continue
 * Reading, and a freshly-added library with unread updates shows just New Chapters. The empty state is
 * reserved for when neither has anything at all.
 */
@Composable
private fun ForYouView(
    content: Content,
    viewModel: HomeViewModel
) {
    val spacing = TanukiTheme.spacing
    if (content.forYouIsEmpty) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            TanukiEmptyState(message = stringResource(Res.string.home_for_you_empty))
        }
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(top = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.lg)
    ) {
        if (content.continueReading.isNotEmpty()) {
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

        if (content.newChapters.isNotEmpty()) {
            // The header stays outside the group and on the screen gutter, so it lines up with Continue
            // Reading's; the group encloses exactly what scrolls. `fill = false` lets the panel hug a short
            // list and stop at the leftover height for a long one — only then does it scroll internally,
            // instead of dragging Continue Reading off the top.
            Column(
                modifier =
                    Modifier
                        .weight(1f, fill = false)
                        .padding(start = spacing.gutter, end = spacing.gutter, bottom = spacing.md),
                // `md` under the header, the gap a Settings section leaves above its own group.
                verticalArrangement = Arrangement.spacedBy(spacing.md)
            ) {
                SectionHeader(title = stringResource(Res.string.home_new_chapters))
                TanukiSectionGroup(modifier = Modifier.weight(1f, fill = false)) {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = spacing.xs)
                    ) {
                        itemsIndexed(
                            content.newChapters,
                            key = { _, item -> item.chapterId.toString() }
                        ) { index, item ->
                            // The rule belongs to the row above it, so it scrolls away with that row
                            // rather than lingering at the group's edge.
                            Column {
                                NewChapterRow(
                                    item = item,
                                    onClick = { viewModel.onNewChapterClick(item) }
                                )
                                if (index < content.newChapters.lastIndex) {
                                    TanukiRowDivider(startInset = NewChapterRowDividerInset)
                                }
                            }
                        }
                    }
                }
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
        contentPadding = PaddingValues(horizontal = spacing.gutter, vertical = spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.md)
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