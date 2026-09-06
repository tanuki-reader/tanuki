package app.tanuki.core.domain.download.model

import app.tanuki.core.domain.manga.model.Manga

/**
 * What the downloads are costing, and which series are costing it.
 *
 * [totalBytes] is measured **on disk**, not summed from the queue records: the two can drift when a
 * write dies mid-flight or the user clears app storage, and the number this screen exists to justify —
 * "should I delete something?" — is only useful if it is the one the OS would report.
 *
 * @property totalBytes Every byte under the downloads root, including any a record no longer knows
 *   about.
 * @property series The breakdown, largest first. A series with a directory but no records still appears:
 *   that is exactly the leftover the user came here to find.
 */
data class DownloadStorageUsage(
    val totalBytes: Long = 0L,
    val series: List<SeriesStorage> = emptyList(),
) {
    /** Whether there is anything on disk at all — what gates the destructive action. */
    val isEmpty: Boolean get() = totalBytes == 0L && series.isEmpty()
}

/**
 * One series' share of the downloads.
 *
 * @property manga The series, for its cover and title.
 * @property chapterCount How many of its chapters are downloaded.
 * @property bytes What they take on disk.
 */
data class SeriesStorage(
    val manga: Manga,
    val chapterCount: Int,
    val bytes: Long,
)
