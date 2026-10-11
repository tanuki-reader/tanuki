package app.tanuki.features.library.composable

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import app.tanuki.assets.designSystem.TanukiTheme
import app.tanuki.core.domain.settings.model.LibraryGridSize
import app.tanuki.features.library.composable.components.LibraryGrid
import app.tanuki.features.library.viewmodel.LibraryItem
import kotlin.uuid.Uuid

/**
 * Renders a loaded library: a scrolling cover grid led by a [countLabel] caption, or — when a query
 * matches nothing — a centered empty-result message. The screen's search/filter chrome lives in the
 * pinned app bar above this view, so the grid (and its count caption) scroll freely beneath it.
 *
 * [countLabel] and [emptyMessage] are UI copy — the caller passes **already-resolved** strings
 * (`stringResource(...)`), never raw resource ids. Keeping resolution out of this composable is what lets
 * it render in `@Preview`: Compose Multiplatform's `stringResource` can't load its resource index under
 * the IDE's layoutlib, so any previewed composable that calls it throws `MissingResourceException`.
 */
@Composable
fun LibraryView(
    modifier: Modifier = Modifier,
    items: List<LibraryItem>,
    gridSize: LibraryGridSize = LibraryGridSize.Medium,
    countLabel: String,
    emptyMessage: String,
    onMangaClick: (Uuid) -> Unit
) {
    LibraryGrid(
        modifier = modifier,
        items = items,
        gridSize = gridSize,
        countLabel = countLabel,
        emptyMessage = emptyMessage,
        onMangaClick = onMangaClick
    )
}

private fun generateMangaList(seed: Int): List<LibraryItem> =
    List(seed) { i ->
        LibraryItem(
            mangaId = Uuid.random(),
            title = "Manga $i",
            coverUrl = null,
            coverHeaders = emptyMap(),
            unreadCount = (i + 1).coerceAtMost(10),
            isDownloaded = i % 2 == 0
        )
    }

@Preview(showBackground = true)
@Composable
private fun LibraryViewPreview() {
    TanukiTheme {
        LibraryView(
            items = generateMangaList(12),
            countLabel = "12 titles",
            emptyMessage = "No titles match",
            onMangaClick = {},
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun LibraryViewEmptyResultPreview() {
    TanukiTheme {
        LibraryView(
            items = emptyList(),
            countLabel = "0 titles",
            emptyMessage = "No titles match “berserk”",
            onMangaClick = {},
            modifier = Modifier.fillMaxSize()
        )
    }
}
