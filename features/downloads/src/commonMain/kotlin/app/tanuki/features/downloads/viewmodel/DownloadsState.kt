package app.tanuki.features.downloads.viewmodel

import app.tanuki.core.domain.download.model.DownloadQueueSeries

/**
 * Immutable UI state for the download queue screen.
 *
 * @property loading `true` until the first queue emission arrives. Distinct from an empty queue on
 *   purpose: flashing "nothing is downloading" at someone who opened this screen *because* something is
 *   downloading is the one thing this screen must never do.
 * @property series the queue grouped by series, in the order the engine will work it — the first chapter
 *   of the first group is the one being fetched right now.
 * @property paused whether the user has stopped the queue. The rows stay exactly as they are while it is
 *   on; what changes is that nothing advances (see `DownloadState`, which has no `Paused` member for
 *   precisely this reason).
 * @property busy `true` while a queue-wide action is in flight, so a second tap can't fire a duplicate
 *   cancel-all across an already-emptying queue.
 */
data class DownloadsState(
    val loading: Boolean = true,
    val series: List<DownloadQueueSeries> = emptyList(),
    val paused: Boolean = false,
    val busy: Boolean = false,
) {
    /** How many chapters the queue holds, across every series — the top bar's subtitle. */
    val chapterCount: Int get() = series.sumOf { it.chapters.size }

    /** Whether there is anything to pause, resume or cancel; drives the top bar's controls. */
    val isEmpty: Boolean get() = series.isEmpty()
}