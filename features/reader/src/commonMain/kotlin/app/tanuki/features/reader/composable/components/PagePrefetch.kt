package app.tanuki.features.reader.composable.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import app.tanuki.features.reader.LocalPageImageLoader
import app.tanuki.features.reader.utils.rememberPageDecodeSize
import app.tanuki.features.reader.utils.toImageRequest
import app.tanuki.features.reader.viewmodel.PagePrefetchBudget
import app.tanuki.features.reader.viewmodel.ReaderItem
import coil3.compose.LocalPlatformContext

/**
 * Enqueues the pages around the focused item into the page cache, so paging forward hits a warm cache and
 * scrolling back doesn't refetch what just left the screen. Walks past transition slides and across chapter
 * boundaries, so the first pages of the next chapter warm up before the reader reaches them. A prefetch that
 * doesn't finish before the user arrives simply becomes a normal load.
 *
 * Warming a page also teaches [ratios] its proportions before it is ever composed, so the long strip can
 * reserve the right height the first time an item appears instead of correcting it once the image lands.
 * The size is picked up from the request's own success callback rather than by awaiting the load, so the
 * warm is not tied to the lifetime of the effect that started it.
 *
 * ## How much is warmed
 *
 * [budget] rather than a constant: `ReaderViewModel` derives it from the connection, so a metered hotspot
 * or a `LOW_SPEED` link narrows the window to two pages ahead and none behind. A budget change does **not**
 * discard what is already warm — it only changes how far the next walk reaches.
 *
 * ## What is cancelled
 *
 * A warm whose page has left the window is **called off**, not left to finish. Fire-and-forget warming
 * meant a fast scroll enqueued and then fully downloaded every page it flew past, on a screen the user was
 * already well beyond. The one exception is the page at [focusedIndex] itself: focus advancing by one moves
 * the visible page out of the *warm* window, and cancelling that download is exactly backwards, so its key
 * is always retained. Leaving the composition cancels everything, so the reader's downloads don't outlive
 * the reader.
 *
 * **Only pages that actually entered the window are enqueued.** Focus moves one item at a time, so re-running
 * the whole window on every move would hand the loader the same requests over and over — each one a fresh
 * job, a cache probe, and on a miss a redundant HTTP call, which costs radio wake-ups more than it costs
 * CPU. [PageWarmer] remembers what has already been handed over so a focus change enqueues at most the one
 * page that just came into range, and forgets a warm that failed or was cancelled so it can be retried the
 * next time focus passes it. A *successful* warm is never re-enqueued; if it is later evicted from the
 * memory cache it simply loads normally (from disk) when its item composes.
 *
 * The warmer is trimmed to the live item list on every window slide, so it stays bounded as the reader's
 * chapter window moves rather than accumulating one entry per page ever read.
 */
@Composable
internal fun PagePrefetch(
    items: List<ReaderItem>,
    focusedIndex: Int,
    budget: PagePrefetchBudget,
    ratios: PageAspectRatios? = null
) {
    val imageLoader = LocalPageImageLoader.current
    val context = LocalPlatformContext.current
    val decodeSize = rememberPageDecodeSize()
    // Keyed on the decode size as well as the loader: a resize or rotation means a different bitmap to warm,
    // so everything becomes worth enqueuing again. Deliberately not keyed on [budget] — a network change
    // must not throw away pages that are already warm.
    val warmer = remember(imageLoader, decodeSize) { PageWarmer() }

    // Pages that left the item list can never be enqueued from here again. Keyed on [items] alone, not on
    // the focus: this is a window-scoped trim, and the reader hands the same list instance back on a page
    // turn, so it runs when the chapter window slides rather than per turn.
    LaunchedEffect(items) {
        warmer.retain(items.mapTo(HashSet(items.size)) { it.key })
    }

    DisposableEffect(warmer) {
        onDispose { warmer.cancelAll() }
    }

    LaunchedEffect(focusedIndex, items, decodeSize, budget) {
        val forward = items.pagesFrom(focusedIndex + 1, step = 1, limit = budget.ahead)
        val backward = items.pagesFrom(focusedIndex - 1, step = -1, limit = budget.behind)
        val window = forward + backward

        val keep = window.mapTo(HashSet(window.size + 1)) { it.key }
        items.getOrNull(focusedIndex)?.let { keep += it.key }
        warmer.cancelOutside(keep)

        for (item in window) {
            warmer.warm(item.key) {
                val request =
                    item
                        .toImageRequest(context, decodeSize)
                        .newBuilder()
                        .listener(
                            onSuccess = { _, result ->
                                ratios?.record(
                                    key = item.key,
                                    width = result.image.width.toFloat(),
                                    height = result.image.height.toFloat()
                                )
                                warmer.settled(item.key)
                            },
                            onError = { _, _ -> warmer.forget(item.key) }
                        ).build()
                val disposable = imageLoader.enqueue(request)
                WarmHandle { disposable.dispose() }
            }
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
