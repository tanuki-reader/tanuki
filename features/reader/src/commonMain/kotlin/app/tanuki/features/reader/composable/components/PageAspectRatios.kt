package app.tanuki.features.reader.composable.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember

/**
 * Remembers the true width:height of every page the reader has resolved, for as long as that page is in
 * the reader's window.
 *
 * The long strip sizes each item from its page's aspect ratio, but a page's real proportions are only known
 * once the image has been decoded (or prefetched). Without a memory of them, every item that has not
 * decoded *yet* — and every item that scrolled out of the `LazyColumn` and got recycled — falls back to a
 * generic placeholder ratio, which has two visible consequences:
 *
 * - the slot is the wrong height, so the page draws letterboxed inside it (narrower than the strip) until
 *   it decodes, next to neighbours that fill the width — pages appear to be at different zoom levels;
 * - the slot's height then *changes* when the real image arrives, shoving everything below it. Scrolling
 *   back up walks into exactly those re-created items, so the strip lurches under the finger.
 *
 * Keeping the ratios means a page that has been seen (or warmed by [PagePrefetch]) is measured at its true
 * height the moment its item is composed, so heights never change after the fact and nothing shifts.
 *
 * Each page gets its own state, so recording one page's ratio only recomposes that page's item. Confined
 * to the main dispatcher: recorded from `LaunchedEffect` bodies, which resume there.
 *
 * Bounded by [retain], which the reader calls with the keys of the items currently on the list. Without
 * it this grows by one entry per page ever seen — small each, but O(pages read) over a session that can
 * run to hundreds of chapters.
 */
@Stable
internal class PageAspectRatios {
    private val ratios = mutableMapOf<String, MutableFloatState>()

    private fun slot(key: String): MutableFloatState =
        ratios.getOrPut(key) { mutableFloatStateOf(DEFAULT_PAGE_ASPECT_RATIO) }

    /** The known ratio for [key], or [DEFAULT_PAGE_ASPECT_RATIO] until one is recorded. Read from a
     *  composition to re-measure that page as soon as its real ratio lands. */
    fun of(key: String): Float = slot(key).floatValue

    /** Records a page's decoded proportions. Degenerate sizes are ignored, so a failed decode leaves the
     *  previous (or placeholder) ratio standing rather than collapsing the item. */
    fun record(
        key: String,
        width: Float,
        height: Float
    ) {
        if (width > 0f && height > 0f) slot(key).floatValue = width / height
    }

    /**
     * Forgets every page outside [keys] — the reader's window slid and those items are gone.
     *
     * Safe against [of] inserting on read: the only caller passes the live item keys, and a key can only
     * enter this map from an item that was in the list. A page that comes back is re-measured on its next
     * decode, which is exactly what happens the first time it is seen.
     */
    fun retain(keys: Set<String>) {
        ratios.keys.retainAll(keys)
    }
}

@Composable
internal fun rememberPageAspectRatios(): PageAspectRatios = remember { PageAspectRatios() }

/** Width:height of a typical portrait manga page, used to reserve a strip item's height until the real
 *  page resolves and its true aspect ratio is known. */
internal const val DEFAULT_PAGE_ASPECT_RATIO = 0.7f