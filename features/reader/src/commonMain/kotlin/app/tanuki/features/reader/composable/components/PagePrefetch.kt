package app.tanuki.features.reader.composable.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import app.tanuki.features.reader.LocalPageImageLoader
import app.tanuki.features.reader.utils.toImageRequest
import app.tanuki.features.reader.viewmodel.ReaderItem
import coil3.compose.LocalPlatformContext

/** How many page images ahead of the current one to warm into the cache. */
private const val PAGE_PREFETCH_AHEAD = 5

//TODO: Check if this logic shouldn't be in viewModel with a single launch effect, instead of one per page.
// It would be more efficient and avoid multiple prefetches when the user scrolls fast.

/**
 * Enqueues the next [PAGE_PREFETCH_AHEAD] page images after the focused item into the page cache, so
 * paging forward hits a warm cache. Walks past transition slides and across chapter boundaries, so the
 * first pages of the next chapter warm up before the reader reaches them. Fire-and-forget: a prefetch
 * that doesn't finish before the user arrives simply becomes a normal load.
 */
@Composable
internal fun PagePrefetch(
    items: List<ReaderItem>,
    focusedIndex: Int
) {
    val imageLoader = LocalPageImageLoader.current
    val context = LocalPlatformContext.current
    LaunchedEffect(focusedIndex, items) {
        var warmed = 0
        var index = focusedIndex + 1
        while (index < items.size && warmed < PAGE_PREFETCH_AHEAD) {
            val item = items[index]
            if (item is ReaderItem.PageItem && (item.page.imageUrl != null || item.page.url != null)) {
                imageLoader.enqueue(item.page.toImageRequest(context))
                warmed++
            }
            index++
        }
    }
}