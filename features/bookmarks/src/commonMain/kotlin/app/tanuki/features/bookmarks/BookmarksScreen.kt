package app.tanuki.features.bookmarks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tanuki.assets.designSystem.TanukiTheme
import app.tanuki.assets.designSystem.component.MangaCoverTile
import app.tanuki.assets.designSystem.component.TanukiDetailTopBar
import app.tanuki.assets.designSystem.component.TanukiEmptyState
import app.tanuki.assets.designSystem.component.TanukiLoadingIndicator
import app.tanuki.assets.designSystem.component.TrashIcon
import app.tanuki.core.navigation.Destination
import app.tanuki.core.navigation.NavEvent
import app.tanuki.features.bookmarks.resources.Res
import app.tanuki.features.bookmarks.resources.bookmarks_chapter_number
import app.tanuki.features.bookmarks.resources.bookmarks_chapter_untitled
import app.tanuki.features.bookmarks.resources.bookmarks_delete
import app.tanuki.features.bookmarks.resources.bookmarks_empty
import app.tanuki.features.bookmarks.resources.bookmarks_row
import app.tanuki.features.bookmarks.resources.bookmarks_title
import app.tanuki.features.bookmarks.viewmodel.BookmarkRow
import app.tanuki.features.bookmarks.viewmodel.BookmarkSeries
import app.tanuki.features.bookmarks.viewmodel.BookmarksState
import app.tanuki.features.bookmarks.viewmodel.BookmarksViewModel
import dev.zacsweers.metrox.viewmodel.metroViewModel
import org.jetbrains.compose.resources.stringResource
import kotlin.uuid.Uuid

/** Cover width in a series header — the compact size the history rows use. */
private val CoverWidth = 40.dp

/** A bookmark's page thumbnail. */
private val ThumbnailWidth = 36.dp

/**
 * Library → ⋯ → Bookmarks: every page bookmark, grouped by series, newest first. A row opens the reader on
 * that page; swiping it away deletes the bookmark.
 */
@Composable
fun BookmarksRoute(
    onNavigate: (Destination) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BookmarksViewModel = metroViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.navEvents.collect { event ->
            when (event) {
                is NavEvent.NavTo -> onNavigate(event.destination)
                NavEvent.NavBack -> onBack()
            }
        }
    }

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TanukiDetailTopBar(title = stringResource(Res.string.bookmarks_title), onBack = onBack)
        when (val current = state) {
            BookmarksState.Loading ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { TanukiLoadingIndicator() }

            is BookmarksState.Content ->
                if (current.series.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        TanukiEmptyState(message = stringResource(Res.string.bookmarks_empty))
                    }
                } else {
                    BookmarkList(current.series, viewModel::onOpen, viewModel::onDelete)
                }
        }
    }
}

@Composable
private fun BookmarkList(
    series: List<BookmarkSeries>,
    onOpen: (BookmarkRow) -> Unit,
    onDelete: (Uuid) -> Unit
) {
    LazyColumn(contentPadding = WindowInsets.navigationBars.asPaddingValues()) {
        series.forEach { group ->
            item(key = "series-${group.mangaId}") { SeriesHeader(group) }
            items(group.bookmarks, key = { it.id.toString() }) { row ->
                SwipeToDelete(onDelete = { onDelete(row.id) }) { BookmarkRowView(row, onClick = { onOpen(row) }) }
            }
        }
    }
}

@Composable
private fun SeriesHeader(series: BookmarkSeries) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = TanukiTheme.spacing.gutter)
                .padding(top = TanukiTheme.spacing.lg, bottom = TanukiTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TanukiTheme.spacing.md)
    ) {
        MangaCoverTile(
            seed = series.mangaId.toString(),
            title = "",
            coverUrl = series.coverUrl,
            coverHeaders = series.coverHeaders,
            onClick = {},
            modifier = Modifier.width(CoverWidth)
        )
        // Content from the source, never localized.
        Text(
            text = series.title,
            style = TanukiTheme.typography.headline,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun BookmarkRowView(
    row: BookmarkRow,
    onClick: () -> Unit
) {
    val chapter =
        row.chapterNumberLabel?.let { stringResource(Res.string.bookmarks_chapter_number, it) }
            ?: row.chapterName
            ?: stringResource(Res.string.bookmarks_chapter_untitled)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .clickable(onClick = onClick)
                .padding(horizontal = TanukiTheme.spacing.gutter, vertical = TanukiTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TanukiTheme.spacing.md)
    ) {
        // The page itself, through the same loader as covers (Cloudflare-aware); an expired source URL
        // leaves the seeded ground.
        MangaCoverTile(
            seed = row.chapterId.toString(),
            title = "",
            coverUrl = row.imageUrl ?: row.localPath,
            coverHeaders = row.imageHeaders,
            onClick = onClick,
            modifier = Modifier.width(ThumbnailWidth)
        )
        Text(
            text = stringResource(Res.string.bookmarks_row, chapter, row.page + 1),
            style = TanukiTheme.typography.body,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Swipe from the end to delete, over a `danger` ground with the trash glyph. */
@Composable
private fun SwipeToDelete(
    onDelete: () -> Unit,
    content: @Composable () -> Unit
) {
    val state = rememberSwipeToDismissBoxState()
    val description = stringResource(Res.string.bookmarks_delete)
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        onDismiss = { value -> if (value == SwipeToDismissBoxValue.EndToStart) onDelete() },
        backgroundContent = {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(TanukiTheme.colors.danger)
                        .padding(horizontal = TanukiTheme.spacing.gutter)
                        .semantics { contentDescription = description },
                contentAlignment = Alignment.CenterEnd
            ) {
                TrashIcon(tint = MaterialTheme.colorScheme.onError)
            }
        }
    ) { content() }
}
