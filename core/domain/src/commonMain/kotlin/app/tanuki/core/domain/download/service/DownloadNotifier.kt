package app.tanuki.core.domain.download.service

import app.tanuki.core.domain.download.model.DownloadProgress

/**
 * Tells the user what the download queue is doing while they are not looking at it, however the platform
 * does that.
 *
 * Bound per platform and kept free of platform types in both directions, exactly like
 * [app.tanuki.core.domain.update.service.LibraryUpdateNotifier] — and, like that one, **permission is the
 * implementation's problem**: each checks its own authorization and does nothing without it, so a caller
 * can always just call.
 *
 * The two platforms implement genuinely different amounts of this, and the interface is shaped so that is
 * not a lie either way:
 *
 *  - **Android** shows [onProgress] as an ongoing notification, because it has to: the queue runs in a
 *    `dataSync` foreground service and the notification *is* what makes that legal.
 *  - **iOS** cannot run a download loop in the background for minutes at all (§7), so there is nothing
 *    for a progress notification to track — its implementation ignores [onProgress] and posts only
 *    [onFinished], when the app is likely not on screen to have shown it.
 *
 * A no-op [onProgress] is therefore a correct implementation, not a stub. What is *not* optional is that
 * every implementation tolerates being called at any point in a run and in any order — a run can be
 * skipped, cancelled, or killed between two calls.
 */
interface DownloadNotifier {
    /**
     * The queue moved on: a different chapter, or another page of the same one.
     *
     * Called often — on the order of once per page — so an implementation that posts something must be
     * cheap and must *replace* rather than accumulate.
     */
    suspend fun onProgress(progress: DownloadProgress)

    /**
     * A run finished having actually done something. Never called for a run that found nothing to do,
     * which is the common case at launch and would otherwise notify the user about a queue they had
     * already emptied.
     *
     * @param downloaded How many chapters were committed to disk.
     * @param failed How many stopped with an error. Cancelled chapters count as neither.
     */
    suspend fun onFinished(
        downloaded: Int,
        failed: Int,
    )

    /**
     * Takes down anything [onProgress] is showing, because the queue is no longer running.
     *
     * Separate from [onFinished] because the two answer different questions — "is work happening" and
     * "what came of it" — and a run that was cancelled or paused needs the first without the second.
     */
    suspend fun clear()
}