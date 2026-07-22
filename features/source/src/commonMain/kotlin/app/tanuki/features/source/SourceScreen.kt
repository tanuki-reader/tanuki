package app.tanuki.features.source

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tanuki.assets.designSystem.TanukiTheme
import app.tanuki.assets.designSystem.component.Segment
import app.tanuki.assets.designSystem.component.TanukiDetailTopBar
import app.tanuki.assets.designSystem.component.TanukiEmptyState
import app.tanuki.assets.designSystem.component.TanukiLoadingIndicator
import app.tanuki.assets.designSystem.component.TanukiSegmentedControl
import app.tanuki.core.navigation.Destination
import app.tanuki.core.navigation.NavEvent
import app.tanuki.features.source.composable.SourceGrid
import app.tanuki.features.source.resources.Res
import app.tanuki.features.source.resources.source_load_error
import app.tanuki.features.source.resources.source_results_empty
import app.tanuki.features.source.resources.source_retry
import app.tanuki.features.source.resources.source_search_hint
import app.tanuki.features.source.viewmodel.Append
import app.tanuki.features.source.viewmodel.SourceBrowseState.Content
import app.tanuki.features.source.viewmodel.SourceBrowseState.Error
import app.tanuki.features.source.viewmodel.SourceBrowseState.Loading
import app.tanuki.features.source.viewmodel.SourceBrowseViewModel
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import org.jetbrains.compose.resources.stringResource

/**
 * Stateful entry point for one source's browse leaf. Resolves [SourceBrowseViewModel] assisted with the
 * [sourceId] the route carried, collects state, and forwards navigation: a [NavEvent.NavTo] (open a
 * tapped manga's detail, after it's cached) to [onNavigate], a [NavEvent.NavBack] to [onBack].
 */
@Composable
fun SourceRoute(
    sourceId: String,
    onNavigate: (Destination) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val viewModel =
        assistedMetroViewModel<SourceBrowseViewModel, SourceBrowseViewModel.Factory> { create(sourceId) }

    LaunchedEffect(viewModel) {
        viewModel.navEvents.collect { event ->
            when (event) {
                is NavEvent.NavTo -> onNavigate(event.destination)
                NavEvent.NavBack -> onBack()
            }
        }
    }

    SourceScreen(modifier = modifier, fallbackTitle = sourceId, viewModel = viewModel, onBack = onBack)
}

@Composable
private fun SourceScreen(
    fallbackTitle: String,
    viewModel: SourceBrowseViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Bound to the VM's live query, not Content.query — the latter only advances once a debounced
    // fetch lands, which would make the field lag a whole round trip behind the user's typing.
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val spacing = TanukiTheme.spacing
    val content = state as? Content
    val title = content?.sourceName ?: fallbackTitle
    // Search now lives inside the top bar (same toggle as the Library), not a persistent pill below it.
    var searchActive by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        TanukiDetailTopBar(
            title = title,
            onBack = onBack,
            collapsed = true,
            // Only offer search once the source is loaded (its name is the placeholder subject).
            searchActive = searchActive && content != null,
            searchQuery = searchQuery,
            onSearchOpen = if (content != null) ({ searchActive = true }) else null,
            onSearchClose = {
                searchActive = false
                viewModel.onQueryChange("")
            },
            onSearchQueryChange = viewModel::onQueryChange,
            searchPlaceholder = stringResource(Res.string.source_search_hint, title)
        )

        when (val current = state) {
            Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { TanukiLoadingIndicator() }

            Error -> Column(
                modifier = Modifier.fillMaxSize().padding(spacing.gutter),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                TanukiEmptyState(message = stringResource(Res.string.source_load_error))
                Text(
                    text = stringResource(Res.string.source_retry),
                    style = TanukiTheme.typography.body,
                    color = TanukiTheme.colors.accent,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(top = spacing.lg)
                        .clickable(onClick = viewModel::onRetry)
                )
            }

            is Content -> {
                if (current.listings.size >= 2) {
                    TanukiSegmentedControl(
                        segments = current.listings.map { Segment(it.key, it.name.value) },
                        selected = current.selectedListingKey ?: current.listings.first().key,
                        onSelect = viewModel::onListingSelect,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = spacing.gutter, vertical = spacing.sm)
                    )
                }

                Box(Modifier.weight(1f)) {
                    if (current.items.isEmpty() && current.append != Append.Loading) {
                        Box(Modifier.fillMaxSize(), Alignment.Center) {
                            TanukiEmptyState(message = stringResource(Res.string.source_results_empty))
                        }
                    } else {
                        SourceGrid(
                            items = current.items,
                            append = current.append,
                            appendRetryLabel = stringResource(Res.string.source_retry),
                            onLoadMore = viewModel::onLoadMore,
                            onRetryAppend = viewModel::onLoadMore,
                            onMangaClick = viewModel::onMangaClick
                        )
                    }
                }
            }
        }
    }
}
