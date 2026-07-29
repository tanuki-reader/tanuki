package app.tanuki.features.reader.composable.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import app.tanuki.features.reader.LocalPageImageLoader
import app.tanuki.features.reader.utils.toImageRequest
import app.tanuki.features.reader.viewmodel.ReaderItem
import coil3.compose.LocalPlatformContext

/** How many page images ahead of the current one to warm into the cache. */
private const val PAGE_PREFETCH_AHEAD = 5

/** How many pages *behind* the current one to keep warm, so turning back doesn't re-download a page that
 *  was on screen moments ago. Smaller than the forward window — reading goes forward. */
private const val PAGE_PREFETCH_BEHIND = 2

//TODO: Check if this logic shouldn't be in viewModel with a single launch effect, instead of one per page.
// It would be more efficient and avoid multiple prefetches when the user scrolls fast.

/**
 * Enqueues the pages around the focused item into the page cache, so paging forward hits a warm cache and
 * scrolling back doesn't refetch what just left the screen. Walks past transition slides and across chapter
 * boundaries, so the first pages of the next chapter warm up before the reader reaches them. Fire-and-forget:
 * a prefetch that doesn't finish before the user arrives simply becomes a normal load.
 *
 * Warming a page also teaches [ratios] its proportions before it is ever composed, so the long strip can
 * reserve the right height the first time an item appears instead of correcting it once the image lands.
 * The size is picked up from the request's own success callback rather than by awaiting the load, so the
 * warm stays detached from this effect: scrolling quickly re-targets the window without cancelling
 * downloads that are already half done.
 */
@Composable
internal fun PagePrefetch(
    items: List<ReaderItem>,
    focusedIndex: Int,
    ratios: PageAspectRatios? = null
) {
    val imageLoader = LocalPageImageLoader.current
    val context = LocalPlatformContext.current
    LaunchedEffect(focusedIndex, items) {
        val forward = items.pagesFrom(focusedIndex + 1, step = 1, limit = PAGE_PREFETCH_AHEAD)
        val backward = items.pagesFrom(focusedIndex - 1, step = -1, limit = PAGE_PREFETCH_BEHIND)
        (forward + backward).forEach { item ->
            val request =
                item.page
                    .toImageRequest(context)
                    .newBuilder()
                    .listener(
                        onSuccess = { _, result ->
                            ratios?.record(
                                key = item.key,
                                width = result.image.width.toFloat(),
                                height = result.image.height.toFloat()
                            )
                        }
                    ).build()
            imageLoader.enqueue(request)
        }
    }
}

/** The next [limit] renderable pages walking from [start] in direction [step], skipping transition slides
 *  and pages with nothing to fetch. */
private fun List<ReaderItem>.pagesFrom(
    start: Int,
    step: Int,
    limit: Int
): List<ReaderItem.PageItem> {
    val found = mutableListOf<ReaderItem.PageItem>()
    var index = start
    while (index in indices && found.size < limit) {
        val item = this[index]
        if (item is ReaderItem.PageItem && (item.page.imageUrl != null || item.page.url != null)) {
            found += item
        }
        index += step
    }
    return found
}
