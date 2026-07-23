package app.tanuki.features.source.composable

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.tanuki.assets.designSystem.TanukiTheme
import app.tanuki.assets.designSystem.component.MangaCoverTile
import app.tanuki.assets.designSystem.component.TanukiLoadingIndicator
import app.tanuki.core.domain.source.model.MangaListing
import app.tanuki.features.source.viewmodel.Append

/** Minimum cover width; the adaptive grid fits as many whole columns as fit, matching the Library. */
private val COVER_MIN_WIDTH = 114.dp

/**
 * The source's cover grid with infinite scroll. Renders the shared [MangaCoverTile] the Library also
 * uses (adaptive columns, cover + title scrim) so browse and library feel like one app; the difference
 * is the tile art comes from a remote [MangaListing.coverUrl] via Coil, and reaching the tail requests
 * the next page.
 *
 * @param items results loaded so far.
 * @param append paging status — drives the footer (spinner / retry / nothing).
 * @param appendRetryLabel localized retry label for a failed load-more.
 * @param onLoadMore called when the grid nears its end and more pages may exist.
 * @param onRetryAppend called from the footer's retry after a failed load-more.
 * @param onMangaClick a cover was tapped.
 */
@Composable
internal fun SourceGrid(
    items: List<MangaListing>,
    append: Append,
    appendRetryLabel: String,
    onLoadMore: () -> Unit,
    onRetryAppend: () -> Unit,
    onMangaClick: (MangaListing) -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = TanukiTheme.spacing
    val gridState = rememberLazyGridState()

    // Latest values without restarting the collector below (which is keyed only on gridState).
    val currentAppend by rememberUpdatedState(append)
    val loadMore by rememberUpdatedState(onLoadMore)

    // Request the next page as the tail scrolls into view. snapshotFlow observes layout changes, so this
    // fires while scrolling rather than only on recomposition, and it re-emits only when the tail index
    // actually changes — so one call per new tail position. The ViewModel guards the rest: it rejects a
    // call while a page is already in flight, and cancels one that a tab switch or search has superseded.
    LaunchedEffect(gridState) {
        snapshotFlow {
            gridState.layoutInfo.visibleItemsInfo
                .lastOrNull()
                ?.index ?: 0
        }.collect { lastVisible ->
            val total = gridState.layoutInfo.totalItemsCount
            if (currentAppend == Append.Idle && total > 0 && lastVisible >= total - PREFETCH_DISTANCE) {
                loadMore()
            }
        }
    }

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(minSize = COVER_MIN_WIDTH),
        modifier = modifier.fillMaxWidth(),
        contentPadding =
            PaddingValues(
                start = spacing.gutter,
                end = spacing.gutter,
                top = spacing.md,
                bottom = spacing.gutter
            ),
        horizontalArrangement = Arrangement.spacedBy(spacing.gridGap),
        verticalArrangement = Arrangement.spacedBy(spacing.gridGap)
    ) {
        items(items, key = { it.sourceMangaKey }) { listing ->
            MangaCoverTile(
                seed = listing.sourceMangaKey,
                title = listing.title,
                coverUrl = listing.coverUrl?.value,
                onClick = { onMangaClick(listing) },
                modifier = Modifier.animateItem()
            )
        }

        if (append == Append.Loading || append == Append.Error) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(
                    Modifier.fillMaxWidth().padding(vertical = spacing.lg),
                    contentAlignment = Alignment.Center
                ) {
                    when (append) {
                        Append.Loading -> {
                            TanukiLoadingIndicator(size = 36.dp)
                        }

                        Append.Error -> {
                            Text(
                                text = appendRetryLabel,
                                style = TanukiTheme.typography.body,
                                color = TanukiTheme.colors.accent,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().clickable(onClick = onRetryAppend)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** How many tiles from the end to trigger the next page load. */
private const val PREFETCH_DISTANCE = 6