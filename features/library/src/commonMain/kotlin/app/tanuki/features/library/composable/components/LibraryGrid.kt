package app.tanuki.features.library.composable.components

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tanuki.assets.designSystem.TanukiTheme
import app.tanuki.assets.designSystem.component.MangaCoverTile
import app.tanuki.assets.designSystem.component.TanukiEmptyState
import app.tanuki.core.domain.settings.model.LibraryGridSize
import app.tanuki.features.library.viewmodel.LibraryItem
import kotlin.uuid.Uuid

/** Minimum cover width per size; the adaptive grid fits as many whole columns as this allows. */
private fun LibraryGridSize.minCoverWidth(): Dp =
    when (this) {
        LibraryGridSize.Small -> 90.dp
        LibraryGridSize.Medium -> 114.dp
        LibraryGridSize.Large -> 150.dp
    }

@Composable
internal fun LibraryGrid(
    modifier: Modifier = Modifier,
    items: List<LibraryItem>,
    gridSize: LibraryGridSize,
    countLabel: String,
    emptyMessage: String,
    onMangaClick: (Uuid) -> Unit
) {
    val spacing = TanukiTheme.spacing
    Crossfade(
        targetState = items.isEmpty(),
        modifier = modifier.fillMaxSize(),
        label = "libraryContent"
    ) { isEmpty ->
        if (isEmpty) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                TanukiEmptyState(message = emptyMessage)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = gridSize.minCoverWidth()),
                modifier = Modifier.fillMaxSize(),
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
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = countLabel,
                        style = TanukiTheme.typography.caption,
                        color = TanukiTheme.colors.textFaint,
                        modifier = Modifier.fillMaxWidth().padding(bottom = spacing.xs)
                    )
                }
                items(items, key = { it.mangaId }) { manga ->
                    MangaCoverTile(
                        seed = manga.mangaId.toString(),
                        title = manga.title,
                        coverUrl = manga.coverUrl,
                        coverHeaders = manga.coverHeaders,
                        onClick = { onMangaClick(manga.mangaId) },
                        unread = manga.unreadCount.toUInt(),
                        downloaded = manga.isDownloaded,
                        modifier = Modifier.animateItem()
                    )
                }
            }
        }
    }
}