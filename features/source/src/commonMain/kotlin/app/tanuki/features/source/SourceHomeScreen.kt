package app.tanuki.features.source

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tanuki.assets.designSystem.TanukiTheme
import app.tanuki.assets.designSystem.component.MangaCoverTile
import app.tanuki.assets.designSystem.component.MangaHeroCard
import app.tanuki.assets.designSystem.component.TanukiDetailTopBar
import app.tanuki.assets.designSystem.component.TanukiEmptyState
import app.tanuki.assets.designSystem.component.TanukiLoadingIndicator
import app.tanuki.core.domain.source.model.SourceListing
import app.tanuki.core.navigation.Destination
import app.tanuki.core.navigation.NavEvent
import app.tanuki.core.presentation.Elapsed
import app.tanuki.core.presentation.ElapsedUnit
import app.tanuki.features.source.resources.Res
import app.tanuki.features.source.resources.source_all_series
import app.tanuki.features.source.resources.source_browse_all
import app.tanuki.features.source.resources.source_in_library
import app.tanuki.features.source.resources.source_latest_chapter
import app.tanuki.features.source.resources.source_listing_count
import app.tanuki.features.source.resources.source_load_error
import app.tanuki.features.source.resources.source_rail_failed
import app.tanuki.features.source.resources.source_retry
import app.tanuki.features.source.resources.source_search_hint
import app.tanuki.features.source.resources.source_see_all
import app.tanuki.features.source.resources.source_time_days
import app.tanuki.features.source.resources.source_time_hours
import app.tanuki.features.source.resources.source_time_just_now
import app.tanuki.features.source.resources.source_time_minutes
import app.tanuki.features.source.resources.source_time_weeks
import app.tanuki.features.source.viewmodel.RailStatus
import app.tanuki.features.source.viewmodel.SourceHomeState
import app.tanuki.features.source.viewmodel.SourceHomeViewModel
import app.tanuki.features.source.viewmodel.CatalogItem
import app.tanuki.features.source.viewmodel.SourceRail
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * A source's home (the Source home redesign): a hero carousel of its Popular listing, cover rails for its
 * other listings, and a preview of its full catalog. "See all" and search open the paged catalog.
 */
@Composable
fun SourceHomeRoute(
    sourceId: String,
    onNavigate: (Destination) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val viewModel = assistedMetroViewModel<SourceHomeViewModel, SourceHomeViewModel.Factory> { create(sourceId) }

    LaunchedEffect(viewModel) {
        viewModel.navEvents.collect { event ->
            when (event) {
                is NavEvent.NavTo -> onNavigate(event.destination)
                NavEvent.NavBack -> onBack()
            }
        }
    }

    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var searchActive by rememberSaveable { mutableStateOf(false) }
    val content = state as? SourceHomeState.Content
    val title = content?.title ?: sourceId

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TanukiDetailTopBar(
            title = title,
            onBack = onBack,
            searchActive = searchActive && content != null,
            searchQuery = content?.searchQuery.orEmpty(),
            onSearchOpen = if (content != null) ({ searchActive = true }) else null,
            onSearchClose = {
                searchActive = false
                viewModel.onSearchQueryChange("")
            },
            onSearchQueryChange = viewModel::onSearchQueryChange,
            searchPlaceholder = stringResource(Res.string.source_search_hint, title),
            onSearchSubmit = { viewModel.onSearchSubmit() }
        )
        when (val current = state) {
            SourceHomeState.Loading -> {
                Box(Modifier.fillMaxSize(), Alignment.Center) { TanukiLoadingIndicator() }
            }

            SourceHomeState.Error -> {
                Column(
                    Modifier.fillMaxSize().padding(TanukiTheme.spacing.gutter),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    TanukiEmptyState(message = stringResource(Res.string.source_load_error))
                    RetryLink(viewModel::onRetry)
                }
            }

            is SourceHomeState.Content -> {
                SourceHome(current, viewModel)
            }
        }
    }
}

@Composable
private fun SourceHome(
    content: SourceHomeState.Content,
    viewModel: SourceHomeViewModel
) {
    val spacing = TanukiTheme.spacing
    LazyColumn(
        contentPadding = PaddingValues(bottom = spacing.xl),
        verticalArrangement = Arrangement.spacedBy(spacing.xl)
    ) {
        item {
            Text(
                text =
                    content.lang + " · " +
                        pluralStringResource(Res.plurals.source_listing_count, content.listingCount, content.listingCount),
                style = TanukiTheme.typography.caption,
                color = TanukiTheme.colors.textFaint,
                modifier = Modifier.padding(horizontal = spacing.gutter)
            )
        }
        // A loaded rail with nothing in it has nothing to show.
        items(content.rails.filterNot { it.status == RailStatus.Loaded && it.items.isEmpty() }, key = { it.listing?.name ?: "all" }) {
            rail ->
            RailSection(rail, viewModel)
        }
    }
}

@Composable
private fun RailSection(
    rail: SourceRail,
    viewModel: SourceHomeViewModel
) {
    val spacing = TanukiTheme.spacing
    val listing = rail.listing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = spacing.gutter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (listing == null) stringResource(Res.string.source_all_series) else listingLabel(listing),
                style = TanukiTheme.typography.title,
                color = MaterialTheme.colorScheme.onBackground
            )
            if (rail.status == RailStatus.Loaded) {
                Text(
                    text = stringResource(if (listing == null) Res.string.source_browse_all else Res.string.source_see_all),
                    style = TanukiTheme.typography.label,
                    color = TanukiTheme.colors.accent,
                    modifier = Modifier.clickable { viewModel.onSeeAll(listing) }
                )
            }
        }
        when (rail.status) {
            RailStatus.Loading -> {
                Box(Modifier.fillMaxWidth().padding(spacing.lg), Alignment.Center) { TanukiLoadingIndicator() }
            }

            RailStatus.Failed -> {
                Column(Modifier.padding(horizontal = spacing.gutter)) {
                    Text(
                        text = stringResource(Res.string.source_rail_failed),
                        style = TanukiTheme.typography.body,
                        color = TanukiTheme.colors.textFaint
                    )
                    RetryLink { viewModel.onRetryRail(listing) }
                }
            }

            RailStatus.Loaded -> {
                when (listing) {
                    SourceListing.POPULAR -> HeroRail(rail.items, viewModel)
                    null -> CatalogPreview(rail.items, viewModel)
                    else -> CoverRail(rail.items, showChapter = listing == SourceListing.LATEST, viewModel)
                }
            }
        }
    }
}

/** Popular: wide hero cards, snapping one at a time. */
@Composable
private fun HeroRail(
    items: List<CatalogItem>,
    viewModel: SourceHomeViewModel
) {
    val state = rememberLazyListState()
    LazyRow(
        state = state,
        flingBehavior = rememberSnapFlingBehavior(state),
        contentPadding = PaddingValues(horizontal = TanukiTheme.spacing.gutter),
        horizontalArrangement = Arrangement.spacedBy(TanukiTheme.spacing.md)
    ) {
        items(items, key = { it.key }) { item ->
            MangaHeroCard(
                seed = item.key,
                title = item.title,
                coverUrl = item.coverUrl,
                coverHeaders = item.coverHeaders,
                subtitle = item.subtitle,
                width = HERO_WIDTH,
                onClick = { viewModel.onMangaClick(item.key) }
            )
        }
    }
}

/** Latest / New: covers, with the newest chapter and its age under each on Latest. */
@Composable
private fun CoverRail(
    items: List<CatalogItem>,
    showChapter: Boolean,
    viewModel: SourceHomeViewModel
) {
    val spacing = TanukiTheme.spacing
    LazyRow(
        contentPadding = PaddingValues(horizontal = spacing.gutter),
        horizontalArrangement = Arrangement.spacedBy(spacing.md)
    ) {
        items(items, key = { it.key }) { item ->
            Column(Modifier.width(RAIL_COVER_WIDTH), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                Cover(item, viewModel)
                if (showChapter) ChapterCaption(item)
            }
        }
    }
}

/** The default catalog's first six, as a three-column grid. */
@Composable
private fun CatalogPreview(
    items: List<CatalogItem>,
    viewModel: SourceHomeViewModel
) {
    val spacing = TanukiTheme.spacing
    Column(Modifier.padding(horizontal = spacing.gutter), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        items.take(PREVIEW_COUNT).chunked(PREVIEW_COLUMNS).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                row.forEach { item -> Box(Modifier.weight(1f)) { Cover(item, viewModel) } }
                repeat(PREVIEW_COLUMNS - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun Cover(
    item: CatalogItem,
    viewModel: SourceHomeViewModel
) {
    Box {
        MangaCoverTile(
            seed = item.key,
            title = item.title,
            coverUrl = item.coverUrl,
            coverHeaders = item.coverHeaders,
            onClick = { viewModel.onMangaClick(item.key) }
        )
        if (item.inLibrary) InLibraryPill(Modifier.align(Alignment.TopStart).padding(TanukiTheme.spacing.sm))
    }
}

/** "In library" in words, not just a glyph — colour or an icon alone carries no meaning. */
@Composable
private fun InLibraryPill(modifier: Modifier = Modifier) {
    Text(
        text = "✓ " + stringResource(Res.string.source_in_library),
        style = TanukiTheme.typography.caption,
        color = MaterialTheme.colorScheme.onSurface,
        modifier =
            modifier
                .background(MaterialTheme.colorScheme.surface, CircleShape)
                .padding(horizontal = TanukiTheme.spacing.sm, vertical = TanukiTheme.spacing.xxs)
    )
}

/** "Ch. 222 · 15 h ago", or whichever half the source reported. */
@Composable
private fun ChapterCaption(item: CatalogItem) {
    val chapter =
        item.latestChapterLabel?.let { stringResource(Res.string.source_latest_chapter, it) } ?: item.latestChapterName
    val ago = item.updatedAgo?.let { elapsedLabel(it) }
    val text = listOfNotNull(chapter, ago).joinToString(" · ")
    if (text.isNotEmpty()) {
        Text(
            text = text,
            style = TanukiTheme.typography.caption,
            color = TanukiTheme.colors.textFaint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun elapsedLabel(elapsed: Elapsed): String =
    when (elapsed.unit) {
        ElapsedUnit.JustNow -> stringResource(Res.string.source_time_just_now)
        ElapsedUnit.Minutes -> pluralStringResource(Res.plurals.source_time_minutes, elapsed.count, elapsed.count)
        ElapsedUnit.Hours -> pluralStringResource(Res.plurals.source_time_hours, elapsed.count, elapsed.count)
        ElapsedUnit.Days -> pluralStringResource(Res.plurals.source_time_days, elapsed.count, elapsed.count)
        ElapsedUnit.Weeks -> pluralStringResource(Res.plurals.source_time_weeks, elapsed.count, elapsed.count)
    }

@Composable
private fun RetryLink(onClick: () -> Unit) {
    Text(
        text = stringResource(Res.string.source_retry),
        style = TanukiTheme.typography.body,
        color = TanukiTheme.colors.accent,
        modifier = Modifier.padding(top = TanukiTheme.spacing.sm).clickable(onClick = onClick)
    )
}

private val HERO_WIDTH = 300.dp
private val RAIL_COVER_WIDTH = 112.dp
private const val PREVIEW_COUNT = 6
private const val PREVIEW_COLUMNS = 3
