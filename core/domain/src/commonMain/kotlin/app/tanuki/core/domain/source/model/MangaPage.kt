package app.tanuki.core.domain.source.model

/**
 * One page of browse/search results — the [entries] a source returned for a single
 * [app.tanuki.core.domain.source.SourceLoader.fetchMangaList] call, plus whether more exist.
 *
 * A bare `List<MangaListing>` cannot express "is there more?": an empty page and the last page are
 * indistinguishable from a short one, so an infinite-scrolling grid has no way to know when to stop
 * requesting. [hasNextPage] makes that explicit and is the source's own answer, since only it knows
 * how its pagination terminates.
 *
 * @property entries The results on this page, in the order the source returned them. May be empty
 *   (a search with no matches, or a page past the end).
 * @property hasNextPage Whether requesting `page + 1` would yield more results. Sources that cannot
 *   tell report `false`, so callers stop rather than paging forever.
 */
data class MangaPage(
    val entries: List<MangaListing> = emptyList(),
    val hasNextPage: Boolean = false
)
