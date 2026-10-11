package app.tanuki.features.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tanuki.assets.designSystem.TanukiTheme
import app.tanuki.assets.designSystem.component.MangaCoverTile
import app.tanuki.assets.designSystem.component.TanukiDetailTopBar
import app.tanuki.assets.designSystem.component.TanukiEmptyState
import app.tanuki.assets.designSystem.component.TanukiLoadingIndicator
import app.tanuki.assets.designSystem.component.TanukiTopBar
import app.tanuki.core.navigation.Destination
import app.tanuki.core.navigation.NavEvent
import app.tanuki.features.search.resources.Res
import app.tanuki.features.search.resources.search_failed
import app.tanuki.features.search.resources.search_hint
import app.tanuki.features.search.resources.search_in_library
import app.tanuki.features.search.resources.search_library_section
import app.tanuki.features.search.resources.search_no_results
import app.tanuki.features.search.resources.search_prompt
import app.tanuki.features.search.resources.search_retry
import app.tanuki.features.search.resources.search_see_all
import app.tanuki.features.search.resources.search_title
import app.tanuki.features.search.viewmodel.GlobalSearchState
import app.tanuki.features.search.viewmodel.GlobalSearchViewModel
import app.tanuki.features.search.viewmodel.SearchRail
import app.tanuki.features.search.viewmodel.SearchStatus
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import org.jetbrains.compose.resources.stringResource

/**
 * Global search: library matches as you type, then one rail per source once the query is submitted.
 * The Search tab shows it with [onBack] `null`; pushed from a recommendation it has a back button.
 */
@Composable
fun GlobalSearchRoute(
    initialQuery: String?,
    onNavigate: (Destination) -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val viewModel = assistedMetroViewModel<GlobalSearchViewModel, GlobalSearchViewModel.Factory> { create(initialQuery) }

    LaunchedEffect(viewModel) {
        viewModel.navEvents.collect { event ->
            when (event) {
                is NavEvent.NavTo -> onNavigate(event.destination)
                NavEvent.NavBack -> onBack?.invoke()
            }
        }
    }

    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val title = stringResource(Res.string.search_title)
    val hint = stringResource(Res.string.search_hint)

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (onBack != null) {
            TanukiDetailTopBar(
                title = title,
                onBack = onBack,
                searchActive = true,
                searchQuery = state.query,
                onSearchClose = onBack,
                onSearchQueryChange = viewModel::onQueryChange,
                searchPlaceholder = hint,
                onSearchSubmit = { viewModel.onSubmit() }
            )
        } else {
            TanukiTopBar(
                title = title,
                searchActive = true,
                searchQuery = state.query,
                onSearchClose = { viewModel.onQueryChange("") },
                onSearchQueryChange = viewModel::onQueryChange,
                searchPlaceholder = hint,
                onSearchSubmit = { viewModel.onSubmit() }
            )
        }
        if (state.showPrompt) {
            Box(Modifier.fillMaxSize().padding(TanukiTheme.spacing.gutter), Alignment.Center) {
                TanukiEmptyState(message = stringResource(Res.string.search_prompt))
            }
        } else {
            Results(state, viewModel)
        }
    }
}

@Composable
private fun Results(
    state: GlobalSearchState,
    viewModel: GlobalSearchViewModel
) {
    val spacing = TanukiTheme.spacing
    LazyColumn(
        contentPadding = PaddingValues(top = spacing.md, bottom = spacing.xl),
        verticalArrangement = Arrangement.spacedBy(spacing.xl)
    ) {
        if (state.library.isNotEmpty()) {
            item(key = "library") {
                Column(Modifier.animateItem(), verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                    Header(stringResource(Res.string.search_library_section), subtitle = null)
                    CoverRow(state.library, key = { it.mangaId.toString() }) { hit ->
                        MangaCoverTile(
                            seed = hit.mangaId.toString(),
                            title = hit.title,
                            coverUrl = hit.coverUrl,
                            coverHeaders = hit.coverHeaders,
                            onClick = { viewModel.onLibraryClick(hit.mangaId) }
                        )
                    }
                }
            }
        }
        items(state.rails, key = { it.sourceId }) { rail ->
            Box(Modifier.animateItem()) { SourceRailSection(rail, viewModel) }
        }
    }
}

@Composable
private fun SourceRailSection(
    rail: SearchRail,
    viewModel: GlobalSearchViewModel
) {
    val spacing = TanukiTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        Header(
            title = rail.name,
            subtitle = rail.lang,
            action = if (rail.status == SearchStatus.Loaded) ({ viewModel.onSeeAll(rail.sourceId) }) else null
        )
        when (rail.status) {
            SearchStatus.Loading -> Box(Modifier.fillMaxWidth().padding(spacing.lg), Alignment.Center) { TanukiLoadingIndicator() }
            SearchStatus.Empty -> Note(stringResource(Res.string.search_no_results))
            SearchStatus.Failed ->
                Column {
                    Note(stringResource(Res.string.search_failed))
                    Text(
                        text = stringResource(Res.string.search_retry),
                        style = TanukiTheme.typography.body,
                        color = TanukiTheme.colors.accent,
                        modifier =
                            Modifier
                                .padding(horizontal = spacing.gutter)
                                .padding(top = spacing.sm)
                                .clickable { viewModel.onRetry(rail.sourceId) }
                    )
                }

            SearchStatus.Loaded ->
                CoverRow(rail.items, key = { it.key }) { item ->
                    Box {
                        MangaCoverTile(
                            seed = item.key,
                            title = item.title,
                            coverUrl = item.coverUrl,
                            coverHeaders = item.coverHeaders,
                            onClick = { viewModel.onResultClick(rail.sourceId, item.key) }
                        )
                        if (item.inLibrary) InLibraryPill(Modifier.align(Alignment.TopStart).padding(spacing.sm))
                    }
                }
        }
    }
}

@Composable
private fun Header(
    title: String,
    subtitle: String?,
    action: (() -> Unit)? = null
) {
    val spacing = TanukiTheme.spacing
    Row(
        Modifier.fillMaxWidth().padding(horizontal = spacing.gutter),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(text = title, style = TanukiTheme.typography.title, color = MaterialTheme.colorScheme.onBackground)
            subtitle?.let { Text(text = it, style = TanukiTheme.typography.caption, color = TanukiTheme.colors.textFaint) }
        }
        if (action != null) {
            Text(
                text = stringResource(Res.string.search_see_all),
                style = TanukiTheme.typography.label,
                color = TanukiTheme.colors.accent,
                modifier = Modifier.clickable(onClick = action)
            )
        }
    }
}

@Composable
private fun <T> CoverRow(
    items: List<T>,
    key: (T) -> Any,
    cover: @Composable (T) -> Unit
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = TanukiTheme.spacing.gutter),
        horizontalArrangement = Arrangement.spacedBy(TanukiTheme.spacing.md)
    ) {
        items(items, key = key) { Box(Modifier.width(RAIL_COVER_WIDTH).animateItem()) { cover(it) } }
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text = text,
        style = TanukiTheme.typography.body,
        color = TanukiTheme.colors.textFaint,
        modifier = Modifier.padding(horizontal = TanukiTheme.spacing.gutter)
    )
}

/** "In library" in words, not just a glyph — colour or an icon alone carries no meaning. */
@Composable
private fun InLibraryPill(modifier: Modifier = Modifier) {
    Text(
        text = "✓ " + stringResource(Res.string.search_in_library),
        style = TanukiTheme.typography.caption,
        color = MaterialTheme.colorScheme.onSurface,
        modifier =
            modifier
                .background(MaterialTheme.colorScheme.surface, CircleShape)
                .padding(horizontal = TanukiTheme.spacing.sm, vertical = TanukiTheme.spacing.xxs)
    )
}

private val RAIL_COVER_WIDTH = 112.dp
