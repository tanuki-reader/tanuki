package app.tanuki.features.reader.composable

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import app.tanuki.assets.designSystem.TanukiTheme
import app.tanuki.assets.designSystem.component.TanukiEmptyState
import app.tanuki.core.domain.manga.model.ReadingMode
import app.tanuki.features.reader.composable.components.PagePrefetch
import app.tanuki.features.reader.composable.components.PagedReader
import app.tanuki.features.reader.composable.components.rememberPageAspectRatios
import app.tanuki.features.reader.composable.components.WebtoonReader
import app.tanuki.features.reader.resources.Res
import app.tanuki.features.reader.resources.reader_no_pages
import app.tanuki.features.reader.viewmodel.PagePrefetchBudget
import app.tanuki.features.reader.viewmodel.ReaderCommand
import app.tanuki.features.reader.viewmodel.ReaderItem
import app.tanuki.features.reader.viewmodel.ReaderState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.stringResource

/**
 * The reader surface. It renders [ReaderState.Content.items] — the concatenated pages of the loaded
 * chapters with transition slides between them — in one pager or long-strip, and wires the two halves of
 * the continuous-reading contract:
 *
 * - **report:** the key of the item the layout has actually placed in focus is pushed to [onItemFocused]
 *   (read from the layout info, so it can never pair a new list with an old index); the ViewModel derives
 *   the chapter, persists progress, and preloads the neighbours.
 * How far ahead pages are warmed is the ViewModel's call too ([prefetchBudget]), since it is the half of
 * the reader that can see the connection.
 *
 * - **command:** the ViewModel drives the viewport back via [commands], each naming an item by key — a
 *   [ReaderCommand.Reanchor] to hold position when the list is re-indexed above the current page, and a
 *   [ReaderCommand.Jump] for the scrubber, chapter buttons and chapter list. The key is resolved against
 *   the list this composition renders, waiting for it to appear (see [awaitIndexOf]).
 */
@Composable
internal fun ReaderContent(
    content: ReaderState.Content,
    commands: Flow<ReaderCommand>,
    prefetchBudget: PagePrefetchBudget,
    onItemFocused: (String) -> Unit,
    onToggleChrome: () -> Unit,
    onBack: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onScrubToPage: (Int) -> Unit
) {
    if (content.items.isEmpty()) {
        TanukiEmptyState(
            message = stringResource(Res.string.reader_no_pages),
            modifier = Modifier.fillMaxSize().padding(TanukiTheme.spacing.gutter)
        )
        return
    }

    val paged = content.readingMode != ReadingMode.WEBTOON && content.readingMode != ReadingMode.CONTINUOUS_VERTICAL
    // Held here rather than inside the strip so that warming a page teaches its proportions even before the
    // strip composes that item, and so switching reading mode doesn't throw the measurements away.
    val ratios = rememberPageAspectRatios()
    // Bound it to the window: a chapter evicted from [content.items] can't be measured from here again.
    LaunchedEffect(content.items) { ratios.retain(content.items.mapTo(mutableSetOf()) { it.key }) }

    // The list this composition renders, readable from the command collectors below.
    val items by rememberUpdatedState(content.items)

    Box(Modifier.fillMaxSize()) {
        if (paged) {
            val pagerState = rememberPagerState(initialPage = content.initialItemIndex) { content.items.size }
            LaunchedEffect(pagerState) {
                snapshotFlow {
                    pagerState.layoutInfo.visiblePagesInfo
                        .firstOrNull { it.index == pagerState.currentPage }
                        ?.key as? String
                }.filterNotNull().collect(onItemFocused)
            }
            // The pager anchors by key during measure (see [PagedReader]), so a re-anchor is usually a no-op
            // — skipped when the page on screen is already the one named — and otherwise the guard against
            // a shift too large for Compose's key→index window. Explicit jumps always have to be applied.
            LaunchedEffect(pagerState, commands) {
                commands.collect { command ->
                    val index = awaitIndexOf(command.key) { items } ?: return@collect
                    when (command) {
                        is ReaderCommand.Jump -> pagerState.scrollToPage(index)
                        is ReaderCommand.Reanchor -> if (pagerState.currentPage != index) pagerState.requestScrollToPage(index)
                    }
                }
            }
            PagePrefetch(content.items, pagerState.currentPage, prefetchBudget, ratios)
            PagedReader(
                items = content.items,
                pagerState = pagerState,
                vertical = content.readingMode == ReadingMode.VERTICAL,
                reverse = content.readingMode == ReadingMode.RIGHT_TO_LEFT,
                onToggleChrome = onToggleChrome
            )
        } else {
            val listState = rememberLazyListState(initialFirstVisibleItemIndex = content.initialItemIndex)
            LaunchedEffect(listState) {
                snapshotFlow { listState.layoutInfo.visibleItemsInfo.firstOrNull()?.key as? String }
                    .filterNotNull()
                    .collect(onItemFocused)
            }
            // The strip keeps its visible item anchored by key when the list changes above it, but only
            // within Compose's ~100-item key window; a long webtoon chapter leaving or arriving shifts
            // further than that. So a re-anchor is applied when the first visible item isn't the one named
            // — with `requestScrollToItem`, which keeps the offset into the page and, unlike
            // `scrollToItem`, doesn't cancel a fling in progress.
            LaunchedEffect(listState, commands) {
                commands.collect { command ->
                    val index = awaitIndexOf(command.key) { items } ?: return@collect
                    when (command) {
                        is ReaderCommand.Jump -> listState.scrollToItem(index)
                        is ReaderCommand.Reanchor ->
                            if (listState.firstVisibleItemIndex != index) {
                                listState.requestScrollToItem(index, listState.firstVisibleItemScrollOffset)
                            }
                    }
                }
            }
            PagePrefetch(content.items, listState.firstVisibleItemIndex, prefetchBudget, ratios)
            WebtoonReader(
                items = content.items,
                listState = listState,
                ratios = ratios,
                onToggleChrome = onToggleChrome
            )
        }

        ReaderChrome(
            visible = content.chromeVisible,
            chapterNumber = content.chapterNumber,
            chapterName = content.chapterName,
            currentPage = content.currentPage,
            pageCount = content.currentPageCount,
            readingMode = content.readingMode,
            hasPreviousChapter = content.hasPreviousChapter,
            hasNextChapter = content.hasNextChapter,
            onBack = onBack,
            onPreviousChapter = onPreviousChapter,
            onNextChapter = onNextChapter,
            onScrubToPage = onScrubToPage
        )
    }
}

/** How long a command waits for its item to reach the rendered list before it is dropped. */
private const val COMMAND_KEY_TIMEOUT_MS = 1_000L

/**
 * Index of [key] in the list being rendered, waiting (briefly) for it to appear: a command is sent right
 * after the ViewModel publishes the list it names, which this composition may not have drawn yet. `null`
 * when it never arrives — the target left the window — and the command is dropped.
 */
private suspend fun awaitIndexOf(
    key: String,
    items: () -> List<ReaderItem>
): Int? =
    withTimeoutOrNull(COMMAND_KEY_TIMEOUT_MS) {
        snapshotFlow { items().indexOfFirst { it.key == key } }.first { it >= 0 }
    }
