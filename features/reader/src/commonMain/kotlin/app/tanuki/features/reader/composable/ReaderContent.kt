package app.tanuki.features.reader.composable

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import app.tanuki.assets.designSystem.TanukiTheme
import app.tanuki.assets.designSystem.component.TanukiEmptyState
import app.tanuki.core.domain.manga.model.ReadingMode
import app.tanuki.features.reader.composable.components.PagePrefetch
import app.tanuki.features.reader.composable.components.PagedReader
import app.tanuki.features.reader.composable.components.WebtoonReader
import app.tanuki.features.reader.resources.Res
import app.tanuki.features.reader.resources.reader_no_pages
import app.tanuki.features.reader.viewmodel.ReaderCommand
import app.tanuki.features.reader.viewmodel.ReaderState
import kotlinx.coroutines.flow.Flow
import org.jetbrains.compose.resources.stringResource

/**
 * The reader surface. It renders [ReaderState.Content.items] — the concatenated pages of the loaded
 * chapters with transition slides between them — in one pager or long-strip, and wires the two halves of
 * the continuous-reading contract:
 *
 * - **report:** the settled item index is pushed to [onItemFocused]; the ViewModel derives the chapter,
 *   persists progress, and preloads the next chapter as boundary approach.
 * - **command:** the ViewModel drives the viewport back via [commands] — a [ReaderCommand.Reanchor] to
 *   hold position when the loaded range grows above the current page (paged layouts only; the strip
 *   anchors by item key), and a [ReaderCommand.Jump] for the scrubber and prev/next-chapter buttons.
 */
@Composable
internal fun ReaderContent(
    content: ReaderState.Content,
    commands: Flow<ReaderCommand>,
    onItemFocused: (Int) -> Unit,
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

    Box(Modifier.fillMaxSize()) {
        if (paged) {
            val pagerState = rememberPagerState(initialPage = content.initialItemIndex) { content.items.size }
            LaunchedEffect(pagerState) {
                snapshotFlow { pagerState.currentPage }.collect(onItemFocused)
            }
            // A pager doesn't anchor by key, so it honors both re-anchors and explicit jumps.
            LaunchedEffect(pagerState, commands) {
                commands.collect { command -> pagerState.scrollToPage(command.index) }
            }
            PagePrefetch(content.items, pagerState.currentPage)
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
                snapshotFlow { listState.firstVisibleItemIndex }.collect(onItemFocused)
            }
            // The strip keeps its visible item anchored by key when the list grows above it, so a
            // re-anchor is a no-op here; only explicit jumps need to be applied.
            LaunchedEffect(listState, commands) {
                commands.collect { command ->
                    if (command is ReaderCommand.Jump) listState.scrollToItem(command.index)
                }
            }
            PagePrefetch(content.items, listState.firstVisibleItemIndex)
            WebtoonReader(
                items = content.items,
                listState = listState,
                onToggleChrome = onToggleChrome
            )
        }

        ReaderChrome(
            content = content,
            onBack = onBack,
            onPreviousChapter = onPreviousChapter,
            onNextChapter = onNextChapter,
            onScrubToPage = onScrubToPage
        )
    }
}