package app.tanuki.update

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.domain.common.service.warn
import app.tanuki.core.domain.update.usecase.UpdateLibrary

/**
 * The Android background run: wakes up on WorkManager's schedule and refreshes the library.
 *
 * Thin by design — everything about *how* a library update behaves (concurrency, failure isolation, the
 * interval check, notifying) is in [UpdateLibrary], shared with iOS. This exists only to be the shape
 * WorkManager can call.
 */
class LibraryUpdateWorker(
    appContext: Context,
    params: WorkerParameters,
    private val updateLibrary: UpdateLibrary,
    private val logger: Logger
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result =
        try {
            updateLibrary(UpdateLibrary.Trigger.Background)
            // Success even when individual series failed: those are already counted, logged, and will
            // be retried on the next period. Asking WorkManager to retry the whole library because one
            // source was down would re-fetch everything that just succeeded.
            Result.success()
        } catch (error: Exception) {
            // UpdateLibrary contains its own failures, so reaching here means something structural
            // (no database, no graph). Worth one backed-off retry rather than waiting a full period.
            logger.warn(TAG, "Library update worker failed", error)
            Result.retry()
        }

    private companion object {
        const val TAG = "LibraryUpdateWorker"
    }
}
