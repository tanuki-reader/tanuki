package app.tanuki.features.browse.viewmodel

import app.tanuki.core.domain.source.model.MangaListing
import app.tanuki.core.domain.source.model.SourceListing

/**
 * State of a single source's browse screen.
 *
 * The first page (and the source's metadata) can fail — no network, a source that doesn't implement
 * `manga_list`, a bad response — so there's a distinct [Error]. Subsequent pages fail more gently:
 * the grid stays, and [Content.append] carries the paging status so a failed load-more shows an inline
 * retry rather than blanking everything already on screen.
 */
sealed interface SourceBrowseState {
    /** The source metadata and first page are loading. */
    data object Loading : SourceBrowseState

    /** The first page (or its metadata) failed. The screen offers a retry. */
    data object Error : SourceBrowseState

    /**
     * Browsing results.
     *
     * @property sourceName human-readable source name for the top bar.
     * @property listings the source's declared browse tabs; empty hides the tab row.
     * @property selectedListingKey the active tab ([SourceListing.key]), or `null` for the default
     *   catalog. Ignored while [query] is non-blank (a search overrides the listing).
     * @property query the live search text; blank means "browse".
     * @property items results accumulated across the pages loaded so far.
     * @property append the infinite-scroll paging status (see [Append]).
     */
    data class Content(
        val sourceName: String,
        val listings: List<SourceListing>,
        val selectedListingKey: String?,
        val query: String,
        val items: List<MangaListing>,
        val append: Append
    ) : SourceBrowseState
}

/** Status of loading the *next* page beneath an already-populated grid. */
enum class Append {
    /** More pages are available and none is loading — the grid may request the next on scroll. */
    Idle,

    /** A next page is in flight (spinner row at the grid's tail). */
    Loading,

    /** The source reported no further pages — stop requesting. */
    End,

    /** The last load-more failed; the tail shows a retry affordance. */
    Error
}
