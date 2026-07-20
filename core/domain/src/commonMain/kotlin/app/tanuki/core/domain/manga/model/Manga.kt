package app.tanuki.core.domain.manga.model

import app.tanuki.core.domain.common.model.Text
import app.tanuki.core.domain.common.model.Url
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A manga/comic series — either in the user's library or merely cached from a browse/read.
 *
 * Aggregates source-provided metadata (title, author, description, cover, publish status, layout)
 * that exists for any manga, with the user's library state ([dateAdded], [readingStatus]) that exists
 * only once the manga is in the library. A manga can be read directly from its source without being
 * added, so the library-only fields are nullable and a set [dateAdded] reflects membership.
 *
 * @property id Local persistence identity.
 * @property source Local id ([Uuid]) of the source this manga belongs to.
 * @property title The series title; see [Text].
 * @property author Author/artist credit; `null` when the source provides none.
 * @property description Synopsis; `null` when the source provides none.
 * @property cover Handle to the cover image (metadata only, never bytes); see [MangaCover].
 * @property url The manga's page on its source — its natural key within that source.
 * @property isDownloaded Whether the user has downloaded this manga.
 * @property unreadCount The number of chapters the user has not yet read.
 * @property dateAdded When the user added this manga to their library, or `null` if it is not in the
 *   library (a cache-only manga). Stored on the `library` row, not on the manga itself.
 * @property lastRead When the user most recently read any chapter of this series, or `null` if never
 *   read. Derived (not stored on the row): the latest `read_at` across the manga's reading history,
 *   populated by the library/detail queries. Persistence writes ignore it.
 * @property readingMode How pages should be laid out when reading; see [ReadingMode]. Source-derived
 *   so it applies even to a manga read outside the library.
 * @property publishStatus The series' publication status as reported by the source; see [PublishStatus].
 * @property readingStatus The user's own reading status for the series, or `null` when the manga is
 *   not in the library. Stored on the `library` row; see [ReadingStatus].
 */
data class Manga(
    val id: Uuid,
    val source: Uuid,
    val title: Text,
    val author: Text?,
    val description: Text?,
    val cover: MangaCover,
    val url: Url,
    val isDownloaded: Boolean = false,
    val unreadCount: UInt = 0u,
    val dateAdded: Instant?,
    val lastRead: Instant? = null,
    val readingMode: ReadingMode,
    val publishStatus: PublishStatus,
    val readingStatus: ReadingStatus?
)