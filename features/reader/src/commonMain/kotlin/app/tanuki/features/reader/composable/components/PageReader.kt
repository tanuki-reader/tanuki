package app.tanuki.features.reader.composable.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.tanuki.features.reader.viewmodel.ReaderItem

/** Paged layouts (L→R, R→L, one-page vertical): one item per swipe, snapping to bounds. Each item is a
 *  full-screen page or a full-screen transition slide, so crossing a chapter boundary is one more swipe. */
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
    if (vertical) {
        VerticalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { index -> slot(index) }
    } else {
        HorizontalPager(
            state = pagerState,
            reverseLayout = reverse,
            modifier = Modifier.fillMaxSize()
        ) { index -> slot(index) }
    }
}