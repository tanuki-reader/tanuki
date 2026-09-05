package app.tanuki.core.domain.download.model

import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The app's record that a chapter is queued for, or already kept on, the device.
 *
 * Local-only state kept *beside* the source-owned chapter rather than on it — the same reasoning as
 * [app.tanuki.core.domain.update.model.ChapterUpdate] and
 * [app.tanuki.core.domain.chapter.model.ReadingProgress]: a source sync rewrites the chapter row, and
 * anything the app decided about that chapter would be clobbered with it. Keyed by [chapterId], so a
 * chapter can be in the queue at most once, and a pruned chapter takes its record with it.
 *
 * This is an **index over the files**, not the files themselves. The authority on "is this chapter
 * actually readable offline" is a committed manifest on disk
 * ([app.tanuki.core.domain.download.service.ChapterDownloadStore]); the two can drift when the user
 * clears app storage or a write dies mid-flight, and reconciling them is a startup sweep rather than
 * something any read has to worry about.
 *
 * @property chapterId The chapter being kept; also this record's identity.
 * @property mangaId Its series, carried so per-series counts and bulk deletes need no join — and so the
 *   on-disk directory (`<mangaId>/<chapterId>`) can be addressed without resolving the chapter first.
 * @property state Where it is in the queue.
 * @property queuedAt When it was enqueued. **The queue's order** — a chapter that fails and is retried
 *   keeps its original stamp rather than going to the back.
 * @property downloadedPages How many pages are on disk. Written per page, so a resumed download and a
 *   restarted app agree without any in-memory queue state being authoritative.
 * @property pageCount How many pages the chapter has, or `null` until its page list has been fetched.
 *   The list is deliberately fetched at download time (some sources' image URLs expire in minutes), so
 *   this is unknown for everything still sitting in the queue.
 * @property bytes Bytes written so far; the completed total once [state] is [DownloadState.Downloaded].
 * @property error Why it stopped, when [state] is [DownloadState.Failed]. `null` otherwise.
 * @property completedAt When the manifest was committed, or `null` if it hasn't been.
 */
data class ChapterDownload(
    val chapterId: Uuid,
    val mangaId: Uuid,
    val state: DownloadState,
    val queuedAt: Instant,
    val downloadedPages: Int = 0,
    val pageCount: Int? = null,
    val bytes: Long = 0L,
    val error: DownloadError? = null,
    val completedAt: Instant? = null,
) {
    /**
     * Fraction of the chapter on disk, or `null` when there is nothing honest to show — the page list
     * hasn't been fetched yet, so any number here would be invented. A caller with `null` should render
     * an indeterminate indicator rather than a zeroed determinate one.
     */
    val progress: Float?
        get() = pageCount?.takeIf { it > 0 }?.let { (downloadedPages.toFloat() / it).coerceIn(0f, 1f) }

    /** Whether this chapter is expected to be readable offline. */
    val isComplete: Boolean get() = state == DownloadState.Downloaded

    /** Whether the queue still has work to do for this chapter. */
    val isPending: Boolean get() = state == DownloadState.Queued || state == DownloadState.Downloading
}
