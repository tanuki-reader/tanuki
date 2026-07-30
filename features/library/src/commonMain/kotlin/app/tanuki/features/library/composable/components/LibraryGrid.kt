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
import androidx.compose.ui.unit.dp
import app.tanuki.assets.designSystem.TanukiTheme
import app.tanuki.assets.designSystem.component.MangaCoverTile
import app.tanuki.assets.designSystem.component.TanukiEmptyState
import app.tanuki.core.domain.manga.model.Manga
import kotlin.uuid.Uuid

/** Minimum cover width; the adaptive grid fits as many whole columns as this allows. */
private val COVER_MIN_WIDTH = 114.dp

@Composable
internal fun LibraryGrid(
    modifier: Modifier = Modifier,
    items: List<Manga>,
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
                columns = GridCells.Adaptive(minSize = COVER_MIN_WIDTH),
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
                items(items, key = { it.id }) { manga ->
                    MangaCoverTile(
                        seed = manga.id.toString(),
                        title = manga.title.value,
                        coverUrl = manga.cover.url?.value,
                        coverHeaders = manga.cover.headers,
                        onClick = { onMangaClick(manga.id) },
                        unread = manga.unreadCount,
                        downloaded = manga.isDownloaded,
                        modifier = Modifier.animateItem()
                    )
                }
            }
        }
    }
}