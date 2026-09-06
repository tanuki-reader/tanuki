package app.tanuki.download

import app.tanuki.appGraph
import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.domain.common.service.info
import app.tanuki.core.domain.common.service.warn
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.launch
import platform.BackgroundTasks.BGProcessingTask
import platform.BackgroundTasks.BGProcessingTaskRequest
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.dateWithTimeIntervalSinceNow

/**
 * The identifier iOS knows this app's download continuation by.
 *
 * Must also appear in `Info.plist` under `BGTaskSchedulerPermittedIdentifiers`, or registering it throws
 * and every submitted request is rejected — iOS refuses to schedule work an app has not declared.
 */
internal const val DOWNLOAD_TASK_ID = "app.tanuki.downloads"

/**
 * Registers the download continuation with iOS.
 *
 * **Must be called before the app finishes launching** — iOS requires every `BGTaskScheduler`
 * identifier to be registered by then and throws otherwise, which is why the Swift host calls this from
 * its initializer rather than the Compose entry point creating it lazily.
 *
 * Registering only says "here is how to run this task"; it does not schedule anything. Requests are
 * submitted at the end of each run by [IosDownloadScheduler].
 */
fun registerDownloadTask() {
    BGTaskScheduler.sharedScheduler.registerForTaskWithIdentifier(
        identifier = DOWNLOAD_TASK_ID,
        usingQueue = null,
    ) { task ->
        (task as? BGProcessingTask)?.let(::runDownloadContinuation)
    }
}

/**
 * Works whatever is left of the queue in a slot iOS granted, then hands it back.
 *
 * A `BGProcessingTask` is the long-running kind — minutes rather than the seconds an app refresh gets —
 * which is why the queue uses it rather than the `BGAppRefreshTask` the library update runs on. It is
 * still entirely at the system's discretion: it is typically granted while the device is charging and
 * idle, and a rarely-opened app may never see one. It is a bonus on top of the foreground run, not the
 * mechanism.
 *
 * Two things have to happen and each is a way the chain breaks if skipped:
 *  - **Complete the task.** iOS watches how promptly an app finishes and hands fewer slots to one that
 *    doesn't.
 *  - **Honour expiration.** Overrunning gets the app killed; cancelling the run and reporting the
 *    failure is far better received. The queue loses nothing by being cancelled — every page already
 *    written stays in its `.part` directory, and the interrupted row is requeued at the next run.
 *
 * The successor is submitted by the run itself (see [IosDownloadScheduler]), not here, because it
 * should only be asked for while there is still something queued.
 */
private fun runDownloadContinuation(task: BGProcessingTask) {
    val graph = appGraph
    val logger: Logger = graph.logger

    val job =
        graph.applicationScope.launch {
            graph.runDownloadQueue()
            task.setTaskCompletedWithSuccess(true)
        }

    task.setExpirationHandler {
        logger.warn(TAG, "The download continuation ran out of time; cancelling")
        job.cancel()
        task.setTaskCompletedWithSuccess(false)
    }
}

/**
 * Asks iOS to work the queue again when it next feels like it.
 *
 * `requiresNetworkConnectivity` because a download run with no network fails every chapter it touches,
 * and `requiresExternalPower` is deliberately **not** set: a queue is work the user explicitly asked
 * for, and refusing to continue it unless the phone is plugged in would mean most queues never resume.
 *
 * Submitting replaces any pending request with the same identifier, so this is safe to call at the end
 * of every run without queueing duplicates.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun submitDownloadContinuation(logger: Logger) {
    val request =
        BGProcessingTaskRequest(DOWNLOAD_TASK_ID).apply {
            requiresNetworkConnectivity = true
            requiresExternalPower = false
            earliestBeginDate = NSDate.dateWithTimeIntervalSinceNow(EARLIEST_DELAY_SECONDS)
        }
    memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        val submitted = BGTaskScheduler.sharedScheduler.submitTaskRequest(request, error.ptr)
        when {
            submitted -> logger.info(TAG, "Download continuation submitted")
            // The usual causes are a missing BGTaskSchedulerPermittedIdentifiers entry and running on a
            // simulator, where these are never delivered. Not fatal: the foreground run is the
            // mechanism, and this is the bonus.
            else ->
                logger.info(TAG, "Could not submit a download continuation: ${error.value?.localizedDescription}")
        }
    }
}

private const val TAG = "DownloadTask"

/**
 * How soon the continuation may run. A minute, not zero: submitting for "now" from the end of a run
 * that just finished the queue asks iOS to wake the app for nothing.
 */
private const val EARLIEST_DELAY_SECONDS = 60.0