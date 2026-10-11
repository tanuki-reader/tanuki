package app.tanuki.core.domain.library.model

/**
 * How the user last left the library: its [filter] and [sort], remembered across launches.
 *
 * Search is deliberately not part of it: a query is a one-off look-up, and coming back to a library
 * that is still filtered by last week's search would read as missing titles.
 */
data class LibraryPreferences(
    val filter: LibraryFilter = LibraryFilter.None,
    val sort: LibrarySort = LibrarySort.Default
)
