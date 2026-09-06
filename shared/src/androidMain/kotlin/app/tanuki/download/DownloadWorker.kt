package app.tanuki.download

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.domain.common.service.warn
import app.tanuki.core.domain.download.usecase.RunDownloadQueue

/**
 * The Android download run: works the queue in a `dataSync` foreground service so it survives the user
 * leaving the app.
 *
 * Thin by design, like [app.tanuki.update.LibraryUpdateWorker] — everything about *how* a queue behaves
 * (ordering, per-chapter failure isolation, pausing, what the user is told) is in [RunDownloadQueue] and
 * the engine beneath it, shared with iOS. This exists to be the shape WorkManager can call, and to hold
 * the foreground-service promotion that only Android has.
 *
 * ### Why it always becomes foreground
 *
 * A plain background worker is subject to the 10-minute execution limit and to Doze; a queue of fifty
 * chapters is neither short nor interruptible-without-cost. Promoting it means the download keeps going
 * with the app closed, which is the entire point of this phase — and the notification the promotion
 * requires is one the user wants anyway, since it is the only thing telling them the queue is moving.
 *
 * The promotion is allowed to fail. `setForeground` throws when the app is in a state Android will not
 * let start a foreground service from (background-start restrictions, a revoked notification
 * permission), and the right response is to download anyway under the ordinary background limits rather
 * than to abandon a queue the user asked for.
 */
class DownloadWorker(
    appContext: Context,
    params: WorkerParameters,
    private val runDownloadQueue: RunDownloadQueue,
    private val notifier: AndroidDownloadNotifier,
    private val logger: Logger,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        runCatching { setForeground(getForegroundInfo()) }
            .onFailure { logger.warn(TAG, "Could not promote the download run to the foreground", it) }

        return try {
            runDownloadQueue()
            // Success even when individual chapters failed: those are already recorded on their rows,
            // shown on the queue screen, and retried by the user. Asking WorkManager to retry the whole
            // queue because one chapter's source was down would re-walk everything that just worked.
            Result.success()
        } catch (error: Exception) {
            // The engine isolates failures per chapter, so reaching here is structural — no database,
            // no graph. Worth one backed-off retry rather than losing the queue until something else
            // enqueues a chapter.
            logger.warn(TAG, "Download worker failed", error)
            Result.retry()
        } finally {
            // The service's notification goes away with the service, but a run that was cancelled
            // (the user cleared the queue) never reaches `RunDownloadQueue`'s own clear.
            runCatching { notifier.clear() }
        }
    }

    /**
     * Called by WorkManager before `doWork` on some versions, and by [doWork] itself.
     *
     * It cannot name a chapter: nothing has been resolved yet at this point, and asking the database
     * here would put a query in front of every promotion to save one frame of a notification that is
     * about to be replaced by the first progress update anyway.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo = notifier.foregroundInfo(progress = null)

    private companion object {
        const val TAG = "DownloadWorker"
    }
}