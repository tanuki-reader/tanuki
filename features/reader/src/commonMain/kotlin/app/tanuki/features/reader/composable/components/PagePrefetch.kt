package app.tanuki.features.reader.composable.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import app.tanuki.features.reader.LocalPageImageLoader
import app.tanuki.features.reader.utils.rememberPageDecodeSize
import app.tanuki.features.reader.utils.toImageRequest
import app.tanuki.features.reader.viewmodel.ReaderItem
import coil3.compose.LocalPlatformContext

/** How many page images ahead of the current one to warm into the cache. */
private const val PAGE_PREFETCH_AHEAD = 5

/** How many pages *behind* the current one to keep warm, so turning back doesn't re-download a page that
 *  was on screen moments ago. Smaller than the forward window — reading goes forward. */
private const val PAGE_PREFETCH_BEHIND = 2

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
 *
 * **Only pages that actually entered the window are enqueued.** Focus moves one item at a time, so re-running
 * the whole window on every move would hand the loader the same seven requests over and over — each one a
 * fresh job, a cache probe, and on a miss a redundant HTTP call, which costs radio wake-ups more than it
 * costs CPU. [warmed] remembers what has already been handed over so a focus change enqueues at most the one
 * page that just came into range. A failed warm is forgotten again, so a page lost to a dropped connection
 * can be retried the next time focus passes it; a *successful* warm is never re-enqueued, and if it is later
 * evicted from the memory cache it simply loads normally (from disk) when its item composes.
 */
@Composable
internal fun PagePrefetch(
    items: List<ReaderItem>,
    focusedIndex: Int,
    ratios: PageAspectRatios? = null
) {
    val imageLoader = LocalPageImageLoader.current
    val context = LocalPlatformContext.current
    val decodeSize = rememberPageDecodeSize()
    // Keyed on the decode size as well as the loader: a resize or rotation means a different bitmap to warm,
    // so everything becomes worth enqueuing again. Confined to the main dispatcher — mutated from the effect
    // below and from Coil's listeners, both of which run there.
    val warmed = remember(imageLoader, decodeSize) { mutableSetOf<String>() }

    LaunchedEffect(focusedIndex, items, decodeSize) {
        val forward = items.pagesFrom(focusedIndex + 1, step = 1, limit = PAGE_PREFETCH_AHEAD)
        val backward = items.pagesFrom(focusedIndex - 1, step = -1, limit = PAGE_PREFETCH_BEHIND)
        for (item in forward + backward) {
            if (!warmed.add(item.key)) continue
            val request =
                item.page
                    .toImageRequest(context, decodeSize)
                    .newBuilder()
                    .listener(
                        onSuccess = { _, result ->
                            ratios?.record(
                                key = item.key,
                                width = result.image.width.toFloat(),
                                height = result.image.height.toFloat()
                            )
                        },
                        onError = { _, _ -> warmed -= item.key }
                    ).build()
            imageLoader.enqueue(request)
        }
    }
}

/** The next [limit] pages walking from [start] in direction [step], skipping transition slides. Every page
 *  that reaches the reader has somewhere to fetch from — `GetReaderPages` drops the ones that do not — so
 *  there is nothing else to filter out here. */
private fun List<ReaderItem>.pagesFrom(
    start: Int,
    step: Int,
    limit: Int
): List<ReaderItem.PageItem> {
    val found = mutableListOf<ReaderItem.PageItem>()
    var index = start
    while (index in indices && found.size < limit) {
        val item = this[index]
        if (item is ReaderItem.PageItem) found += item
        index += step
    }
    return found
}
