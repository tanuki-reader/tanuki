package app.tanuki.features.reader.composable.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.tanuki.features.reader.viewmodel.ReaderItem

/** Long-strip layout (webtoons): items stacked in a continuous scroll, no per-item snapping. Transition
 *  slides fill the viewport, so scrolling past a chapter's last page lands on a full "up next" panel and
 *  scrolling on flows into the following chapter. */
@Composable
internal fun WebtoonReader(
    items: List<ReaderItem>,
    listState: LazyListState,
    onToggleChrome: () -> Unit
) {
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        items(items, key = { it.key }) { item ->
            ReaderItemContent(
                item = item,
                onToggleChrome = onToggleChrome,
                fillHeight = false,
                transitionModifier = Modifier.fillParentMaxHeight().fillMaxWidth()
            )
        }
    }
}