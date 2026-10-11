package app.tanuki.core.domain.metadata.model

import kotlin.uuid.Uuid

/**
 * How a library series is tied to its metadata. One per manga; absent until enrichment first ran.
 *
 * @property anilistId the matched series, or `null` when none is known (the source links nowhere and
 *   the user hasn't searched).
 * @property mangaDexId the series' MangaDex id when known — where its volume covers come from.
 * @property useSourceInfo the user's per-series switch: show the source's title, cover and synopsis
 *   instead of the metadata's.
 * @property coverChoice the cover the user picked, overriding both the source's and the metadata's.
 */
data class TrackerLink(
    val mangaId: Uuid,
    val anilistId: Long?,
    val malId: Long?,
    val mangaDexId: String?,
    val matchedBy: MatchedBy,
    val useSourceInfo: Boolean = false,
    val coverChoice: String? = null
)

enum class MatchedBy {
    /** The source published the ids (exact). */
    Source,

    /** The user confirmed a search result. */
    User
}
