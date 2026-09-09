package app.tanuki.features.reader.viewmodel

/**
 * How many page images either side of the visible one the reader may warm into its cache.
 *
 * Not a constant, because the same seven pages cost nothing on Wi-Fi and cost the user money on a
 * tethered phone. `ReaderViewModel` derives the current budget from
 * [app.tanuki.core.domain.common.service.ConnectionObserver] and publishes it beside `uiState`; the
 * prefetch effect reads it as the limit for its forward and backward walk.
 *
 * Compose-free and outside `ReaderState.Content` on purpose: `Content`'s identity is load-bearing (an
 * equal-but-fresh instance costs the reader a full recomposition of its item list), and a network change
 * has no business rebuilding the page list.
 *
 * @property ahead pages to warm ahead of the visible one.
 * @property behind pages to keep warm behind it.
 */
data class PagePrefetchBudget(
    val ahead: Int,
    val behind: Int
) {
    companion object {
        /**
         * The unconstrained window: five ahead, two behind. Forward is larger because reading goes
         * forward; the two behind exist so turning back doesn't re-download the page that was on screen
         * a moment ago.
         */
        val Full = PagePrefetchBudget(ahead = 5, behind = 2)

        /**
         * The window on a link worth being frugal with — metered, `LOW_SPEED`, or offline. Two ahead is
         * still enough to cover a normal page turn without a visible fetch; nothing is kept behind,
         * because re-fetching a page the user chose to go back to is a cost they asked for, while
         * speculatively holding it is not.
         */
        val Frugal = PagePrefetchBudget(ahead = 2, behind = 0)
    }
}
