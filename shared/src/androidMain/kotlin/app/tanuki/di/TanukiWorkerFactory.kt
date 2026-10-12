package app.tanuki.di

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.domain.download.usecase.RunDownloadQueue
import app.tanuki.core.domain.source.usecase.CheckSourceUpdates
import app.tanuki.core.domain.update.usecase.UpdateLibrary
import app.tanuki.download.AndroidDownloadNotifier
import app.tanuki.download.DownloadWorker
import app.tanuki.update.LibraryUpdateWorker
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Builds the app's injected workers with their dependencies from the Metro graph.
 *
 * WorkManager instantiates workers reflectively and can only call a `(Context, WorkerParameters)`
 * constructor, so an injected worker needs a factory — installed by `TanukiApplication` through
 * `Configuration.Provider`. This is what lets a worker take constructor dependencies like every other
 * class in the app instead of reaching for a global.
 *
 * It lives beside the graph rather than beside any one worker: it now builds two, and a factory sitting
 * inside `LibraryUpdateWorker.kt` while constructing a `DownloadWorker` would be the sort of thing you
 * only find by grepping.
 */
@Inject
@SingleIn(AppScope::class)
class TanukiWorkerFactory(
    private val updateLibrary: UpdateLibrary,
    private val checkSourceUpdates: CheckSourceUpdates,
    private val runDownloadQueue: RunDownloadQueue,
    private val downloadNotifier: AndroidDownloadNotifier,
    private val logger: Logger,
) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? =
        when (workerClassName) {
            LibraryUpdateWorker::class.qualifiedName ->
                LibraryUpdateWorker(appContext, workerParameters, updateLibrary, checkSourceUpdates, logger)

            DownloadWorker::class.qualifiedName ->
                DownloadWorker(appContext, workerParameters, runDownloadQueue, downloadNotifier, logger)

            // Null hands the worker back to WorkManager's default factory rather than failing — so a
            // worker added later that doesn't need injection still works without touching this.
            else -> null
        }
}