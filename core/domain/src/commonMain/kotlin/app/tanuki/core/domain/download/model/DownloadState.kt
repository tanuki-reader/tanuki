package app.tanuki.core.domain.download.model

/**
 * Where a chapter is in the download queue.
 *
 * Deliberately **without a `Paused` member.** Pausing is a property of the *queue*, not of a chapter: a
 * paused chapter that resumes has to keep its place, and "resume all" would otherwise mean rewriting
 * every row — which loses the ordering it was supposed to preserve. So the pause is a single app
 * preference and rows stay [Queued] while it is on.
 *
 * [Downloaded] is the state of the *row*; the state of the *disk* is whether a `manifest.json` exists
 * (see [app.tanuki.core.domain.download.service.ChapterDownloadStore]). The two can drift — the user can
 * clear app storage — and the row is the index, never the authority.
 */
enum class DownloadState {
    /** Waiting its turn. Also the state a chapter returns to when the queue is paused. */
    Queued,

    /** Being fetched right now. At most a handful of chapters are ever in this state at once. */
    Downloading,

    /** Every page is on disk and the manifest is committed. */
    Downloaded,

    /** Gave up on this chapter; see [ChapterDownload.error]. Whatever was fetched is kept for a retry. */
    Failed,
}
