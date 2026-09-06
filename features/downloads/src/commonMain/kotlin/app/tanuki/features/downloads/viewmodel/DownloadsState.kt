package app.tanuki.features.downloads.viewmodel

import app.tanuki.core.domain.download.model.DownloadHold
import app.tanuki.core.domain.download.model.DownloadQueueSeries

/**
 * Immutable UI state for the download queue screen.
 *
 * @property loading `true` until the first queue emission arrives. Distinct from an empty queue on
 *   purpose: flashing "nothing is downloading" at someone who opened this screen *because* something is
 *   downloading is the one thing this screen must never do.
 * @property series the queue grouped by series, in the order the engine will work it — the first chapter
 *   of the first group is the one being fetched right now.
 * @property hold why the queue is not advancing, when it isn't. The rows stay exactly as they are
 *   whatever the reason (see `DownloadState`, which has no `Paused` member for precisely this reason),
 *   so this is the *only* thing that can explain a full queue standing still.
 * @property busy `true` while a queue-wide action is in flight, so a second tap can't fire a duplicate
 *   cancel-all across an already-emptying queue.
 * @property runsInBackground whether the platform keeps working the queue once the user leaves the app.
 *   `false` on iOS, where a download loop cannot run in the background for minutes — the screen says so
 *   rather than letting the user find out by returning to a queue that has not moved.
 */
data class DownloadsState(
    val loading: Boolean = true,
    val series: List<DownloadQueueSeries> = emptyList(),
    val hold: DownloadHold = DownloadHold.None,
    val busy: Boolean = false,
    val runsInBackground: Boolean = true,
) {
    /** How many chapters the queue holds, across every series — the top bar's subtitle. */
    val chapterCount: Int get() = series.sumOf { it.chapters.size }

    /** Whether there is anything to pause, resume or cancel; drives the top bar's controls. */
    val isEmpty: Boolean get() = series.isEmpty()

    /** Whether the user's own pause is on — what the top bar's toggle reflects. */
    val paused: Boolean get() = hold == DownloadHold.Paused
}