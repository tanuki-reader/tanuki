package app.tanuki.features.reader.composable.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.tanuki.features.reader.viewmodel.ReaderItem

/**
 * Paged layouts (L→R, R→L, one-page vertical): one item per swipe, snapping to bounds. Each item is a
 * full-screen page or a full-screen transition slide, so crossing a chapter boundary is one more swipe.
 *
 * Both pagers are keyed by [ReaderItem.key], which is what keeps the reader on its page when the item
 * list is re-indexed under it — a chapter's pages inserted above the viewport, or the chapter two behind
 * evicted. A pager restores its position by key during measure exactly as the webtoon `LazyColumn` does,
 * but only when it is *given* keys; without them it holds an index, and every window shift would jump.
 * `ReaderCommand.Reanchor` stays as the fallback for shifts Compose's key→index lookup cannot see.
 */
@Composable
internal fun PagedReader(
    items: List<ReaderItem>,
    pagerState: PagerState,
    vertical: Boolean,
    reverse: Boolean,
    onToggleChrome: () -> Unit
) {
    val slot: @Composable (Int) -> Unit = { index ->
        ReaderItemContent(
            item = items[index],
            onToggleChrome = onToggleChrome,
            transitionModifier = Modifier.fillMaxSize()
        )
    }
    val key: (Int) -> Any = { index -> items[index].key }
    if (vertical) {
        VerticalPager(
            state = pagerState,
            key = key,
            modifier = Modifier.fillMaxSize()
        ) { index -> slot(index) }
    } else {
        HorizontalPager(
            state = pagerState,
            reverseLayout = reverse,
            key = key,
            modifier = Modifier.fillMaxSize()
        ) { index -> slot(index) }
    }
}