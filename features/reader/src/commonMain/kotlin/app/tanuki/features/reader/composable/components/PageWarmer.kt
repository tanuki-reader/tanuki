package app.tanuki.features.reader.composable.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember

/** A warm that has been handed to the image loader and can still be called off. */
internal fun interface WarmHandle {
    fun cancel()
}

/**
 * Bookkeeping for [PagePrefetch]: which pages have already been handed to the image loader, and which of
 * those are still in flight and therefore still cancellable.
 *
 * Two sets rather than one, because the two questions have different answers. *Warmed* is "don't enqueue
 * this again" — focus moves one item at a time, so re-running the whole window on every move would hand
 * the loader the same requests over and over, each a fresh job, a cache probe, and on a miss a redundant
 * HTTP call, which costs radio wake-ups more than it costs CPU. *In flight* is "this download can still
 * be stopped", which is a strictly smaller set that empties as warms land.
 *
 * A key leaves both together, so a page that is cancelled or fails is warmable again the next time focus
 * passes it, while a page that succeeded is never re-enqueued — if its bitmap is later evicted from the
 * memory cache it simply loads from disk when its item composes.
 *
 * Deliberately free of Coil and Compose types: [WarmHandle] is the whole seam, which is what lets the
 * cancellation rules be unit-tested in a module that has no Compose test infrastructure.
 *
 * Confined to the main dispatcher — driven from `LaunchedEffect` bodies and from Coil's listeners, both
 * of which resume there.
 */
@Stable
internal class PageWarmer {
    private val warmed = mutableSetOf<String>()
    private val inFlight = mutableMapOf<String, WarmHandle>()

    /**
     * Hands [key] to the loader through [start], unless it has already been warmed. [start] is a lambda
     * rather than a ready handle so building the request costs nothing on the common path, where the page
     * is already warm.
     */
    fun warm(
        key: String,
        start: () -> WarmHandle
    ) {
        if (!warmed.add(key)) return
        val handle = start()
        // A warm that already failed inside [start] has taken itself out of [warmed]; storing its handle
        // would leave an entry nothing can ever settle.
        if (key in warmed) inFlight[key] = handle
    }

    /** The warm for [key] finished. It stays warmed; it is simply no longer cancellable. */
    fun settled(key: String) {
        inFlight -= key
    }

    /** The warm for [key] failed. Forgotten entirely, so a page lost to a dropped connection is retried
     *  the next time focus passes it. */
    fun forget(key: String) {
        warmed -= key
        inFlight -= key
    }

    /**
     * Stops every warm still in flight whose page is not in [keys], and forgets it.
     *
     * This is what keeps a fast scroll from downloading in full every page it flew past. The caller
     * passes the warm window *plus the visible page*: focus advancing by one moves the page the user is
     * looking at out of the window, and cancelling its download would be exactly backwards.
     *
     * A cancelled warm forfeits the proportions it would have taught [PageAspectRatios]. That costs
     * nothing — the page is out of the window, so nothing is measuring it, and it is measured on its next
     * decode like any page seen for the first time.
     */
    fun cancelOutside(keys: Set<String>) {
        val doomed = inFlight.keys.filterNot { it in keys }
        for (key in doomed) {
            inFlight.remove(key)?.cancel()
            warmed -= key
        }
    }

    /**
     * Forgets every page outside [keys] — the reader's chapter window slid and those items are gone, so
     * they can never be enqueued from here again and remembering them only grows the set.
     *
     * Cancels their in-flight warms too: a page that has left the item list is one the reader cannot show
     * without loading it again from scratch, so finishing its download buys nothing.
     */
    fun retain(keys: Set<String>) {
        cancelOutside(keys)
        warmed.retainAll(keys)
    }

    /** Stops everything still in flight. Called when the prefetcher leaves the composition — the reader
     *  closed, or the reading mode flipped — so its downloads don't outlive the screen that wanted them. */
    fun cancelAll() {
        cancelOutside(emptySet())
    }
}

@Composable
internal fun rememberPageWarmer(): PageWarmer = remember { PageWarmer() }
