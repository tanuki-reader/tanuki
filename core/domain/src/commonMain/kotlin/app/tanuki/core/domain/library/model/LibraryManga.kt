package app.tanuki.core.domain.library.model

import app.tanuki.core.domain.common.model.Text
import app.tanuki.core.domain.manga.model.MangaCover
import app.tanuki.core.domain.manga.model.PublishStatus
import app.tanuki.core.domain.manga.model.ReadingStatus
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * One row of the user's library, carrying exactly what the grid renders and what
 * [app.tanuki.core.domain.library.usecase.ObserveLibrary] filters, searches and sorts by — and nothing
 * else.
 *
 * The library is read as a live query invalidated by writes to six tables (`ChapterDownloadDao.setProgress`
 * alone fires once per downloaded *page*), so its row shape is paid for on every one of those
 * invalidations, per title. A full [app.tanuki.core.domain.manga.model.Manga] would drag `description` —
 * unbounded prose — plus `author`, the permalink, `sourceMangaKey`, `readingMode`, `filteredScanlators`
 * and the sync-bookkeeping columns through that loop, and allocate a [Text] and a parsed
 * [app.tanuki.core.domain.common.model.Url] per row for fields the grid never draws. Keeping the shape in
 * the type rather than in a comment is what makes that non-recurrable: nothing on this screen *can* reach
 * a description. The full model stays where it is genuinely wanted — the detail screen.
 *
 * [cover] stays a whole [MangaCover] rather than a flattened url/header pair, and by now that is its
 * whole content: its headers are documented as travelling with it precisely because a cover renders from
 * the database in half a dozen places with no source in scope to re-ask. When this type was introduced
 * the cover also carried a cache-busting `lastModified`, left here to keep that question open rather
 * than decide it by omission; schema v12 closed it by deleting the field, so the two remaining columns
 * are both ones the grid draws with.
 *
 * @property unreadCount chapters with no progress, already excluding the manga's hidden scanlation
 *   groups — the count the badge shows, which is why `filteredScanlators` itself is not here. The filter
 *   is applied by the query; the grid wants only its result.
 * @property isDownloaded whether at least one chapter is committed to disk.
 * @property dateAdded when the series joined the library. Non-null, unlike
 *   [app.tanuki.core.domain.manga.model.Manga.dateAdded]: this row exists *because* there is a `library`
 *   row to join, so membership is not in question here the way it is for an arbitrary manga.
 * @property lastRead the most recent read of any of its chapters; `null` when never read.
 * @property readingStatus non-null for the same reason as [dateAdded] — it is a column of the `library`
 *   row itself.
 */
data class LibraryManga(
    val id: Uuid,
    val title: Text,
    val cover: MangaCover,
    val unreadCount: UInt,
    val isDownloaded: Boolean,
    val dateAdded: Instant,
    val lastRead: Instant?,
    val readingStatus: ReadingStatus,
    val publishStatus: PublishStatus
)
