package app.tanuki.features.source.viewmodel

import app.tanuki.core.domain.source.model.MangaListing
import app.tanuki.core.domain.source.model.SourceListing

/**
 * The screen's persistent chrome — the top bar's title and the listing tab row. Derived from the
 * source's metadata and the tab the user picked, **never** from a page load, so it stays mounted and
 * unchanged while [SourceBrowseState] cycles through Loading on every tab switch, search and retry.
 * Deriving it from the results (as it once was) meant the bar and tabs were composed inside the
 * `Results` branch and vanished for a frame whenever that branch went away.
 *
 * @property title the resolved source name, or `null` until metadata lands (the route's id stands in).
 * @property listings the contract listings this source declared; fewer than two hides the tab row.
 * @property selected the effective tab — the picked one, or the first declared listing by default.
 */
data class SourceChrome(
    val title: String? = null,
    val listings: List<SourceListing> = emptyList(),
    val selected: SourceListing? = null
)

/**
 * State of a single source's browse screen.
 *
 * The first page (and the source's metadata) can fail — no network, a source that doesn't implement
 * `manga_list`, a bad response — so there's a distinct [Error]. Subsequent pages fail more gently:
 * the grid stays, and [Results.append] carries the paging status so a failed load-more shows an inline
 * retry rather than blanking everything already on screen.
 *
 * Browsing and searching are **separate states** rather than one state with a query field, because they
 * are different screens: a source can't search *within* a listing (see `BrowseSource`), so while a query
 * is active the listing tabs don't apply to what's on screen and must not be shown. Modelling that as
 * [Browsing] vs. [Searching] means the UI reads the tabs off the state that has them, instead of
 * inferring "am I searching?" from a blank string.
 */
sealed interface SourceBrowseState {
    /** The source metadata and first page are loading. */
    data object Loading : SourceBrowseState

    /** The first page (or its metadata) failed. The screen offers a retry. */
    data object Error : SourceBrowseState

    /**
     * What [Browsing] and [Searching] share: a grid of results and its paging status. Load-more works
     * off this, so it doesn't care which of the two it's paging.
     *
     * The source's name and tab row are deliberately **not** here — they belong to [SourceChrome],
     * which outlives any single load.
     *
     * @property items results accumulated across the pages loaded so far.
     * @property append the infinite-scroll paging status (see [Append]).
     */
    sealed interface Results : SourceBrowseState {
        val items: List<MangaListing>
        val append: Append
    }

    /** Browsing one of the source's listings — the default state, with the tab row visible. */
    data class Browsing(
        override val items: List<MangaListing>,
        override val append: Append
    ) : Results

    /**
     * Showing results for a search. No tabs: the query replaced the listing rather than filtering it, so
     * a selected tab would be a lie. An empty [items] here means "nothing matched *this query*", which
     * the UI can say precisely — unlike an empty listing, which means the source returned nothing at all.
     *
     * @property query the search text these results are for. This is the *fetched* query, so it lags the
     *   text field by a debounce; the field binds to the ViewModel's live query instead.
     */
    data class Searching(
        val query: String,
        override val items: List<MangaListing>,
        override val append: Append
    ) : Results
}

/**
 * Replaces the results and paging status, keeping whichever [Results] variant this is. Lets the paging
 * code update the grid without re-deciding whether the screen is browsing or searching.
 */
internal fun SourceBrowseState.Results.withResults(
    items: List<MangaListing>,
    append: Append
): SourceBrowseState.Results =
    when (this) {
        is SourceBrowseState.Browsing -> copy(items = items, append = append)
        is SourceBrowseState.Searching -> copy(items = items, append = append)
    }

/** Status of loading the *next* page beneath an already-populated grid. */
enum class Append {
    /** More pages are available, and none is loading — the grid may request the next on scroll. */
    Idle,

    /** The next page is in flight (spinner row at the grid's tail). */
    Loading,

    /** The source reported no further pages — stop requesting. */
    End,

    /** The last load-more failed; the tail shows a retry affordance. */
    Error
}