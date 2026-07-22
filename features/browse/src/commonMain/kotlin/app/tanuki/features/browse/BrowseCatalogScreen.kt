package app.tanuki.features.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
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
import app.tanuki.assets.designSystem.component.TanukiEmptyState
import app.tanuki.assets.designSystem.component.TanukiLoadingIndicator
import app.tanuki.assets.designSystem.component.TanukiTopBar
import app.tanuki.core.domain.source.model.SourceMeta
import app.tanuki.core.navigation.Destination
import app.tanuki.core.navigation.NavEvent
import app.tanuki.features.browse.composable.SourceRow
import app.tanuki.features.browse.resources.Res
import app.tanuki.features.browse.resources.browse_source_subtitle
import app.tanuki.features.browse.resources.browse_sources_empty_body
import app.tanuki.features.browse.resources.browse_title
import app.tanuki.features.browse.viewmodel.BrowseCatalogState.Content
import app.tanuki.features.browse.viewmodel.BrowseCatalogState.Loading
import app.tanuki.features.browse.viewmodel.BrowseCatalogViewModel
import dev.zacsweers.metrox.viewmodel.metroViewModel
import org.jetbrains.compose.resources.stringResource

/**
 * Stateful entry point for the Browse tab root. Resolves [BrowseCatalogViewModel] from Metro, collects
 * its state, and forwards navigation to [onNavigate] (opening a source's browse screen). Mirrors
 * [app.tanuki.features.library.LibraryRoute]'s shape — the only place the feature touches nav mechanics.
 */
@Composable
fun BrowseCatalogRoute(
    modifier: Modifier = Modifier,
    viewModel: BrowseCatalogViewModel = metroViewModel(),
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
    BrowseCatalogScreen(modifier = modifier, viewModel = viewModel)
}

@Composable
private fun BrowseCatalogScreen(
    modifier: Modifier = Modifier,
    viewModel: BrowseCatalogViewModel
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Source search filters the list client-side — enumerating a few installed sources needs no query.
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchActive by rememberSaveable { mutableStateOf(false) }
    val spacing = TanukiTheme.spacing

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        TanukiTopBar(
            title = stringResource(Res.string.browse_title),
            searchActive = searchActive,
            searchQuery = searchQuery,
            onSearchOpen = { searchActive = true },
            onSearchClose = { searchActive = false; searchQuery = "" },
            onSearchQueryChange = { searchQuery = it }
        )

        when (val current = state) {
            Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { TanukiLoadingIndicator() }

            is Content if current.sources.isEmpty() ->
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    TanukiEmptyState(message = stringResource(Res.string.browse_sources_empty_body))
                }

            is Content -> {
                val visible = current.sources.filter {
                    it.name.value.contains(searchQuery.trim(), ignoreCase = true)
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(spacing.gutter),
                    verticalArrangement = Arrangement.spacedBy(spacing.sm)
                ) {
                    items(visible, key = { it.id }) { source ->
                        SourceRow(
                            name = source.name.value,
                            subtitle = source.subtitle(),
                            onClick = { viewModel.onSourceClick(source.id) }
                        )
                    }
                }
            }
        }
    }
}

/** "language · vN" when the source reports a version, else just its language tag. */
@Composable
private fun SourceMeta.subtitle(): String =
    version?.let { stringResource(Res.string.browse_source_subtitle, lang.value, it) } ?: lang.value
