package app.tanuki.core.domain.download.model

import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The index written into a downloaded chapter's directory, and the thing that makes the download
 * *complete*.
 *
 * Two properties hang off this file and both matter more than its contents:
 *
 *  - **Its existence is the completion flag.** Pages are written into a sibling `.part` directory and
 *    the manifest is committed last, by an atomic move of the whole directory. So a crash mid-chapter
 *    can leave a partial `.part` (which is exactly what a resume wants) but can never leave a
 *    half-chapter that reads as downloaded.
 *  - **It is what the offline read path trusts.** Page order comes from [pages], not from sorting file
 *    names and not from re-asking the source — so a chapter reads the same offline as it did online,
 *    forever, even if the source later renumbers or delists it.
 *
 * @property version Format version, so a future layout change can be migrated (or the directory
 *   discarded and re-fetched) instead of being misread. Bump [CURRENT_VERSION] when the shape changes.
 * @property chapterId The chapter this directory holds. Redundant with the directory name, and kept
 *   anyway: it is what lets a directory found on disk be identified without trusting its path.
 * @property pages Every page, in reading order, index `0` first and contiguous.
 * @property totalBytes Sum of [DownloadedPage.bytes]. Stored rather than recomputed so a storage
 *   breakdown costs one file read per chapter instead of one `stat` per page.
 * @property downloadedAt When the chapter was committed.
 */
data class DownloadManifest(
    val version: Int,
    val chapterId: Uuid,
    val pages: List<DownloadedPage>,
    val totalBytes: Long,
    val downloadedAt: Instant,
) {
    /** How many pages the chapter has. Always `pages.size` — a manifest is only ever written complete. */
    val pageCount: Int get() = pages.size

    companion object {
        /** The version [DownloadManifest]s are written at today. */
        const val CURRENT_VERSION: Int = 1
    }
}

/**
 * One page inside a committed chapter directory.
 *
 * @property index Zero-based position in the chapter, matching [app.tanuki.core.domain.source.model.Page.index].
 * @property file The **file name only**, never a path. Chapter directories move (a `.part` is renamed
 *   on commit) and the whole downloads root moves between installs and platforms, so anything absolute
 *   stored here would be stale the moment it was written. The store joins it to a real path on read.
 * @property bytes Size of the file on disk.
 */
data class DownloadedPage(
    val index: Int,
    val file: String,
    val bytes: Long,
)
