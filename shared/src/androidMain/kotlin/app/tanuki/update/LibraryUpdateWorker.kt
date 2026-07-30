package app.tanuki.update

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.domain.common.service.warn
import app.tanuki.core.domain.update.usecase.UpdateLibrary
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

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

/**
 * Builds [LibraryUpdateWorker] with its dependencies from the Metro graph.
 *
 * WorkManager instantiates workers reflectively and can only call a `(Context, WorkerParameters)`
 * constructor, so an injected worker needs a factory — installed by `TanukiApplication` through
 * `Configuration.Provider`. This is what lets the worker take constructor dependencies like every other
 * class in the app instead of reaching for a global.
 */
@Inject
@SingleIn(AppScope::class)
class TanukiWorkerFactory(
    private val updateLibrary: UpdateLibrary,
    private val logger: Logger
) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters
    ): ListenableWorker? =
        when (workerClassName) {
            LibraryUpdateWorker::class.qualifiedName ->
                LibraryUpdateWorker(appContext, workerParameters, updateLibrary, logger)

            // Null hands the worker back to WorkManager's default factory rather than failing — so a
            // worker added later that doesn't need injection still works without touching this.
            else -> null
        }
}