package app.tanuki.features.reader.composable

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
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
import app.tanuki.features.reader.viewmodel.ReaderSheet
import app.tanuki.features.reader.viewmodel.ReaderState
import app.tanuki.features.reader.viewmodel.ReaderViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
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
 * - **command:** the ViewModel drives the viewport via [commands]: a [ReaderCommand.Jump] for the scrubber,
 *   chapter buttons and chapter list, naming an item by key, resolved against the list this composition
 *   renders once it appears (see [awaitIndexOf]). Holding position when the list is re-indexed above the
 *   current page is the layouts' own job here (see [PinOnReindex]), so [ReaderCommand.Reanchor] is ignored.
 */
@Composable
internal fun ReaderContent(
    content: ReaderState.Content,
    viewModel: ReaderViewModel,
    prefetchBudget: PagePrefetchBudget
) {
    val commands = viewModel.commands
    val onItemFocused: (String) -> Unit = viewModel::onItemFocused
    val onToggleChrome: () -> Unit = viewModel::onToggleChrome
    val onScrollingChange: (Boolean) -> Unit = viewModel::onScrollingChange
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
        // Keyed on the mode so a switch builds a fresh pager/strip, which parks on `initialItemIndex` —
        // the page that was on screen (the ViewModel moves it there on a mode change).
        key(content.readingMode) {
            if (paged) {
                val pagerState = rememberPagerState(initialPage = content.initialItemIndex) { content.items.size }
                LaunchedEffect(pagerState) {
                    snapshotFlow {
                        pagerState.layoutInfo.visiblePagesInfo
                            .firstOrNull { it.index == pagerState.currentPage }
                            ?.key as? String
                    }.filterNotNull().collect(onItemFocused)
                }
                LaunchedEffect(pagerState) { snapshotFlow { pagerState.isScrollInProgress }.collect(onScrollingChange) }
                PinOnReindex(
                    items = content.items,
                    onScreen = {
                        pagerState.layoutInfo.visiblePagesInfo
                            .firstOrNull { it.index == pagerState.currentPage }
                            ?.let { it.index to it.key }
                    },
                    pin = { index -> pagerState.requestScrollToPage(index, pagerState.currentPageOffsetFraction) }
                )
                LaunchedEffect(pagerState, commands) {
                    commands.collect { command ->
                        if (command !is ReaderCommand.Jump) return@collect // Reanchor: see [PinOnReindex]
                        val index = awaitIndexOf(command.key) { items } ?: return@collect
                        pagerState.scrollToPage(index)
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
                LaunchedEffect(listState) { snapshotFlow { listState.isScrollInProgress }.collect(onScrollingChange) }
                PinOnReindex(
                    items = content.items,
                    onScreen = { listState.layoutInfo.visibleItemsInfo.firstOrNull()?.let { it.index to it.key } },
                    pin = { index -> listState.requestScrollToItem(index, listState.firstVisibleItemScrollOffset) }
                )
                LaunchedEffect(listState, commands) {
                    commands.collect { command ->
                        if (command !is ReaderCommand.Jump) return@collect // Reanchor: see [PinOnReindex]
                        val index = awaitIndexOf(command.key) { items } ?: return@collect
                        listState.scrollToItem(index)
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
        }

        val uriHandler = LocalUriHandler.current
        ReaderChrome(
            visible = content.chromeVisible,
            chapterNumberLabel = content.chapterNumberLabel,
            chapterName = content.chapterName,
            mangaTitle = content.mangaTitle,
            isBookmarked = content.isCurrentPageBookmarked,
            currentPage = content.currentPage,
            pageCount = content.currentPageCount,
            readingMode = content.readingMode,
            hasPreviousChapter = content.hasPreviousChapter,
            hasNextChapter = content.hasNextChapter,
            onBack = viewModel::onBack,
            onToggleBookmark = viewModel::onToggleBookmark,
            onOpenInBrowser = { uriHandler.openUri(content.chapterUrl) },
            onPreviousChapter = viewModel::onPreviousChapter,
            onNextChapter = viewModel::onNextChapter,
            onScrubToPage = viewModel::onScrubToPage,
            onScrubbingChange = viewModel::onScrubbingChange,
            onOpenChapters = { viewModel.onOpenSheet(ReaderSheet.Chapters) },
            onOpenSettings = { viewModel.onOpenSheet(ReaderSheet.Settings) }
        )
    }

    ReaderSheets(
        content = content,
        onDismiss = viewModel::onDismissSheet,
        onChaptersTabChange = viewModel::onChaptersTabChange,
        onChapterSelected = viewModel::onChapterSelected,
        onBookmarkSelected = viewModel::onBookmarkSelected,
        onReadingModeChange = viewModel::onReadingModeChange,
        onBackgroundChange = viewModel::onBackgroundChange
    )
}

/**
 * Keeps the item on screen in place when [items] is re-indexed above it: a chapter evicted from the
 * window, or the previous one prepended. Both layouts re-find their first item by key, but only within
 * [COMPOSE_KEY_REACH] items of where it was; past that they keep the old *index*, which now names a page
 * further on (often in another chapter). The reader then reports that page as the one being read and the
 * window moves again, so one long chapter leaving could run the reader through several chapters on its own.
 *
 * Runs as a [SideEffect]: after the new list is composed and before it is measured, so [onScreen] still
 * reads the old layout and [pin] lands in the very measure that would have slipped. Within reach it leaves
 * the layout to it, because a pin cancels a fling in progress and Compose's own re-anchoring doesn't.
 *
 * This is why the layouts ignore [ReaderCommand.Reanchor]: it reaches the collector before the new list is
 * composed, so its key resolves against the old list and reads as "already there".
 */
@Composable
private fun PinOnReindex(
    items: List<ReaderItem>,
    onScreen: () -> Pair<Int, Any>?,
    pin: (Int) -> Unit
) {
    val pinnedFor = remember { arrayOfNulls<List<ReaderItem>>(1) }
    SideEffect {
        if (pinnedFor[0] === items) return@SideEffect
        pinnedFor[0] = items
        val (was, key) = onScreen() ?: return@SideEffect
        val index = items.indexOfFirst { it.key == key }
        if (index >= 0 && abs(index - was) > COMPOSE_KEY_REACH) pin(index)
    }
}

/** How far from its last index a lazy layout looks an item's key up (Compose's `NearestItemsExtraItemCount`). */
private const val COMPOSE_KEY_REACH = 100

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
