package app.tanuki.core.domain.download.usecase

import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.domain.common.service.warn
import app.tanuki.core.domain.download.model.DownloadProgress
import app.tanuki.core.domain.download.model.DownloadQueueSeries
import app.tanuki.core.domain.download.model.DownloadState
import app.tanuki.core.domain.download.service.DownloadNotifier
import app.tanuki.core.domain.download.usecase.DownloadRunResult.Completed
import app.tanuki.core.domain.download.usecase.DownloadRunResult.Skipped
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Use case: work the queue, telling the user about it — the entry point every platform runner calls.
 *
 * [DownloadManager] deliberately knows nothing about notifications: it is an engine over three ports,
 * and giving it a fourth for the user's benefit would put presentation in the one class that must stay
 * about bytes. So the two are composed here instead, and the platform runners (a WorkManager job, a
 * `BGProcessingTask`, the app-lifetime scope) call this rather than the engine.
 *
 * ### The notification is *observed*, not reported
 *
 * Progress is not pushed out of the engine. It is collected from [ObserveDownloadQueue] — the same flow
 * the queue screen renders — for as long as the run lasts. That is worth more than the indirection
 * costs:
 *
 *  - The engine keeps its three dependencies and gains no reason to resolve a series title mid-download.
 *  - The notification and the queue screen cannot disagree, because there is one source and it is the
 *    database, which is already the authority on a run that survived a process death.
 *  - A lagging collector costs a stale frame on a notification and nothing else, unlike the pause flag
 *    (see [DownloadManager]) where the same shape was a correctness problem and was removed for it.
 *
 * ### What is *not* announced
 *
 * A run that found nothing to do says nothing. Every app launch asks the queue to run, and a "0
 * downloaded, 0 failed" notification each time would train the user to dismiss the ones that matter.
 * A [Skipped] run — another run already had the queue — touches the notifier not at all: the run that
 * owns the queue owns what is on screen about it, and clearing here would take down a live progress
 * notification belonging to work still in flight.
 */
sealed interface RunDownloadQueue {
    /** @return What the run did — the engine's own result, passed through unchanged. */
    suspend operator fun invoke(): DownloadRunResult

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val downloadManager: DownloadManager,
        private val observeDownloadQueue: ObserveDownloadQueue,
        private val notifier: DownloadNotifier,
        private val logger: Logger,
    ) : RunDownloadQueue {
        override suspend fun invoke(): DownloadRunResult =
            coroutineScope {
                val watch = launch { announceProgress() }
                val result =
                    try {
                        downloadManager.run()
                    } finally {
                        watch.cancel()
                    }

                when (result) {
                    // Another run owns the queue, and therefore owns what is shown about it.
                    Skipped -> Unit
                    is Completed -> {
                        notifier.clear()
                        if (result.downloaded > 0 || result.failed > 0) {
                            notifier.onFinished(downloaded = result.downloaded, failed = result.failed)
                        }
                    }
                }
                result
            }

        /**
         * Mirrors the queue into the notifier until cancelled.
         *
         * A failure here must never take the run down with it — a notification is the least important
         * thing happening — so everything short of cancellation is logged and swallowed.
         */
        private suspend fun announceProgress() {
            try {
                observeDownloadQueue()
                    .map { it.currentProgress() }
                    .distinctUntilChanged()
                    .collect { progress -> progress?.let { notifier.onProgress(it) } }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                logger.warn(TAG, "Could not report download progress", error)
            }
        }

        private companion object {
            const val TAG = "RunDownloadQueue"
        }
    }
}

/**
 * The one chapter worth naming right now, and how many are behind it.
 *
 * The chapter being *fetched* wins over the first in line: the queue is worked in order, so those are
 * normally the same row, but between two chapters there is a moment with nothing downloading and naming
 * the next one keeps the notification from blinking empty. `null` when the queue holds nothing at all.
 *
 * Failed chapters are counted in [DownloadProgress.remaining] like any other. They are still in the
 * queue as far as the screen is concerned, and a notification that quietly stopped counting them would
 * disagree with it.
 */
private fun List<DownloadQueueSeries>.currentProgress(): DownloadProgress? {
    val rows = flatMap { series -> series.chapters.map { series.manga to it } }
    if (rows.isEmpty()) return null
    val index =
        rows
            .indexOfFirst { (_, queued) -> queued.download.state == DownloadState.Downloading }
            .takeIf { it >= 0 } ?: 0
    val (manga, queued) = rows[index]
    return DownloadProgress(
        manga = manga,
        chapter = queued.chapter,
        download = queued.download,
        remaining = rows.size - index - 1,
    )
}