package app.tanuki.features.source.viewmodel

import app.tanuki.core.domain.source.model.MangaListing
import app.tanuki.core.domain.source.model.SourceListing
import app.tanuki.core.presentation.Elapsed
import app.tanuki.core.presentation.elapsedSince
import kotlin.time.Instant

/** A source's home: rails of its listings, then its default catalog, under the source's name. */
sealed interface SourceHomeState {
    data object Loading : SourceHomeState

    /** The source couldn't be resolved at all (not installed, or its metadata failed). */
    data object Error : SourceHomeState

    /**
     * @property title the source's name.
     * @property lang the source's language tag, for the subtitle under the title.
     * @property rails one per declared listing in contract order, then the default catalog
     *   ([SourceRail.listing] `null`, the "All series" preview).
     * @property searchQuery the live search text; submitting it opens the catalog's search.
     */
    data class Content(
        val title: String,
        val lang: String,
        val rails: List<SourceRail>,
        val searchQuery: String
    ) : SourceHomeState {
        /** How many listings the source declares, for "English · 3 listings". */
        val listingCount: Int get() = rails.count { it.listing != null }
    }
}

/**
 * One rail. [listing] `null` is the source's default catalog. [items] is empty unless [status] is
 * [RailStatus.Loaded]; a loaded rail with no items is hidden by the UI.
 */
data class SourceRail(
    val listing: SourceListing?,
    val status: RailStatus,
    val items: List<CatalogItem>
)

enum class RailStatus { Loading, Loaded, Failed }

/**
 * A series on a rail, in plain values for Swift.
 *
 * @property key the source-native key; what [SourceHomeViewModel.onMangaClick] takes.
 * @property title source content, not localizable.
 * @property subtitle the hero card's line: up to three genres, else the author; `null` when neither.
 * @property latestChapterLabel the newest chapter's number for display ("222", "10.5"), when the source
 *   reports one.
 * @property latestChapterName the newest chapter's own name, for sources that don't number chapters.
 * @property updatedAgo when the series last got a chapter, for "15 h ago".
 * @property inLibrary whether the series is already in the user's library.
 */
data class CatalogItem(
    val key: String,
    val title: String,
    val coverUrl: String?,
    val coverHeaders: Map<String, String>,
    val subtitle: String?,
    val latestChapterLabel: String?,
    val latestChapterName: String?,
    val updatedAgo: Elapsed?,
    val inLibrary: Boolean
)

internal fun MangaListing.toItem(
    inLibrary: Boolean,
    now: Instant
): CatalogItem =
    CatalogItem(
        key = sourceMangaKey,
        title = title,
        coverUrl = coverUrl?.value,
        coverHeaders = coverHeaders,
        subtitle = genres.take(MAX_GENRES).joinToString(" · ").ifEmpty { author },
        latestChapterLabel = latestChapter?.display,
        latestChapterName = latestChapterName,
        updatedAgo = updatedAt?.let { elapsedSince(it, now) },
        inLibrary = inLibrary
    )

private const val MAX_GENRES = 3
