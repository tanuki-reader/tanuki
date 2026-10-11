package app.tanuki.features.search.viewmodel

import kotlin.uuid.Uuid

/**
 * The global search screen: what the user typed, the library series it matches (live), and one rail per
 * source for the last submitted search.
 *
 * @property showPrompt nothing typed and nothing searched yet — the screen shows its "search all your
 *   sources" prompt instead of results.
 */
data class GlobalSearchState(
    val query: String = "",
    val library: List<LibraryHit> = emptyList(),
    val rails: List<SearchRail> = emptyList()
) {
    val showPrompt: Boolean get() = query.isBlank() && rails.isEmpty()
}

/** A library series whose title matches what is typed. */
data class LibraryHit(
    val mangaId: Uuid,
    val title: String,
    val coverUrl: String?,
    val coverHeaders: Map<String, String>
)

/** One source's results for the submitted search. [lang] is the source's BCP-47 tag. */
data class SearchRail(
    val sourceId: String,
    val name: String,
    val lang: String,
    val status: SearchStatus,
    val items: List<SearchItem>
)

enum class SearchStatus { Loading, Loaded, Empty, Failed }

/** A series a source found. [key] is the source's own id for it. */
data class SearchItem(
    val key: String,
    val title: String,
    val coverUrl: String?,
    val coverHeaders: Map<String, String>,
    val inLibrary: Boolean
)
