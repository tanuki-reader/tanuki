package app.tanuki.features.library.composable

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import app.tanuki.assets.designSystem.TanukiTheme
import app.tanuki.core.domain.common.model.Text
import app.tanuki.core.domain.common.service.uuidV7
import app.tanuki.core.domain.library.model.LibraryManga
import app.tanuki.core.domain.manga.model.MangaCover
import app.tanuki.core.domain.manga.model.PublishStatus.PUBLISHING_FINISHED
import app.tanuki.core.domain.manga.model.ReadingStatus.READING
import app.tanuki.features.library.composable.components.LibraryGrid
import app.tanuki.features.library.viewmodel.LibraryState.DisplayLibrary
import kotlin.time.Clock
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
    state: DisplayLibrary,
    countLabel: String,
    emptyMessage: String,
    onMangaClick: (Uuid) -> Unit
) {
    val items = state.items
    LibraryGrid(
        modifier = modifier,
        items = items,
        countLabel = countLabel,
        emptyMessage = emptyMessage,
        onMangaClick = onMangaClick
    )
}

private fun generateMangaList(seed: Int): List<LibraryManga> =
    List(seed) { i ->
        LibraryManga(
            id = uuidV7(),
            title = Text("Manga $i"),
            cover =
                MangaCover(mangaId = uuidV7(), url = null),
            unreadCount = (i.toUShort() + 1u).coerceAtMost(10u),
            isDownloaded = i % 2 == 0,
            dateAdded = Clock.System.now(),
            lastRead = null,
            readingStatus = READING,
            publishStatus = PUBLISHING_FINISHED
        )
    }

@Preview(showBackground = true)
@Composable
private fun LibraryViewPreview() {
    val items = generateMangaList(12)
    TanukiTheme {
        LibraryView(
            state = DisplayLibrary(items = items),
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
            state =
                DisplayLibrary(
                    items = emptyList(),
                    searchQuery = "berserk"
                ),
            countLabel = "0 titles",
            emptyMessage = "No titles match “berserk”",
            onMangaClick = {},
            modifier = Modifier.fillMaxSize()
        )
    }
}