package app.tanuki.features.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tanuki.assets.designSystem.TanukiTheme
import app.tanuki.assets.designSystem.component.Segment
import app.tanuki.assets.designSystem.component.TanukiDetailTopBar
import app.tanuki.assets.designSystem.component.TanukiEmptyState
import app.tanuki.assets.designSystem.component.TanukiLoadingIndicator
import app.tanuki.assets.designSystem.component.TanukiSearchBar
import app.tanuki.assets.designSystem.component.TanukiSegmentedControl
import app.tanuki.core.navigation.Destination
import app.tanuki.core.navigation.NavEvent
import app.tanuki.features.browse.composable.BrowseGrid
import app.tanuki.features.browse.resources.Res
import app.tanuki.features.browse.resources.browse_load_error
import app.tanuki.features.browse.resources.browse_results_empty
import app.tanuki.features.browse.resources.browse_retry
import app.tanuki.features.browse.resources.browse_search_hint
import app.tanuki.features.browse.viewmodel.Append
import app.tanuki.features.browse.viewmodel.SourceBrowseState.Content
import app.tanuki.features.browse.viewmodel.SourceBrowseState.Error
import app.tanuki.features.browse.viewmodel.SourceBrowseState.Loading
import app.tanuki.features.browse.viewmodel.SourceBrowseViewModel
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import org.jetbrains.compose.resources.stringResource

/**
 * Stateful entry point for one source's browse screen. Resolves [SourceBrowseViewModel] assisted with
 * the [sourceId] the route carried, collects state, and forwards navigation: a [NavEvent.NavTo] (open a
 * tapped manga's detail, after it's cached) to [onNavigate], a [NavEvent.NavBack] to [onBack].
 */
@Composable
fun SourceBrowseRoute(
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

    SourceBrowseScreen(modifier = modifier, fallbackTitle = sourceId, viewModel = viewModel, onBack = onBack)
}

@Composable
private fun SourceBrowseScreen(
    fallbackTitle: String,
    viewModel: SourceBrowseViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val spacing = TanukiTheme.spacing
    val title = (state as? Content)?.sourceName ?: fallbackTitle

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        TanukiDetailTopBar(title = title, onBack = onBack, collapsed = true)

        when (val current = state) {
            Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { TanukiLoadingIndicator() }

            Error -> Column(
                modifier = Modifier.fillMaxSize().padding(spacing.gutter),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                TanukiEmptyState(message = stringResource(Res.string.browse_load_error))
                Text(
                    text = stringResource(Res.string.browse_retry),
                    style = TanukiTheme.typography.body,
                    color = TanukiTheme.colors.accent,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(top = spacing.lg)
                        .clickable(onClick = viewModel::onRetry)
                )
            }

            is Content -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = spacing.gutter, vertical = spacing.sm)
                ) {
                    TanukiSearchBar(
                        value = current.query,
                        onValueChange = viewModel::onQueryChange,
                        placeholder = stringResource(Res.string.browse_search_hint, current.sourceName)
                    )
                    if (current.listings.size >= 2) {
                        TanukiSegmentedControl(
                            segments = current.listings.map { Segment(it.key, it.name.value) },
                            selected = current.selectedListingKey ?: current.listings.first().key,
                            onSelect = viewModel::onListingSelect,
                            modifier = Modifier.fillMaxWidth().padding(top = spacing.sm)
                        )
                    }
                }

                Box(Modifier.weight(1f)) {
                    if (current.items.isEmpty() && current.append != Append.Loading) {
                        Box(Modifier.fillMaxSize(), Alignment.Center) {
                            TanukiEmptyState(message = stringResource(Res.string.browse_results_empty))
                        }
                    } else {
                        BrowseGrid(
                            items = current.items,
                            append = current.append,
                            appendRetryLabel = stringResource(Res.string.browse_retry),
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
