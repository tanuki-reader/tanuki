package app.tanuki.update

import app.tanuki.appGraph
import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.domain.common.service.warn
import app.tanuki.core.domain.settings.model.LibraryUpdateInterval
import app.tanuki.core.domain.update.usecase.UpdateLibrary
import kotlinx.coroutines.launch
import platform.BackgroundTasks.BGAppRefreshTask
import platform.BackgroundTasks.BGTaskScheduler

/**
 * The interval the next background refresh should be submitted for.
 *
 * Module-level state because iOS forces it: `BGTaskScheduler` has no periodic request, so every run
 * has to submit its own successor, and the handler that does it is a static launch handler with no
 * injected collaborators. It is kept in step by [BgTaskLibraryUpdateScheduler], which writes it
 * whenever the user's interval is applied.
 *
 * The default matters for one real case: iOS can launch the app straight into a background refresh,
 * with no UI and therefore no `KeepLibraryUpdateScheduled` having run yet. Falling back to the default
 * interval keeps the chain alive instead of letting it end at the first background-only launch.
 */
private var nextInterval: LibraryUpdateInterval = LibraryUpdateInterval.Default

/** Records the interval future re-submissions should use. Called by the scheduler. */
internal fun rememberInterval(interval: LibraryUpdateInterval) {
    nextInterval = interval
}

/**
 * Registers the library-update background task with iOS.
 *
 * **Must be called before the app finishes launching** — iOS requires every task identifier to be
 * registered by then and throws otherwise, which is why the Swift host calls this from its
 * initializer rather than the Compose entry point creating it lazily.
 *
 * Registering only says "here is how to run this task"; it does not schedule anything. The first
 * request is submitted by [BgTaskLibraryUpdateScheduler] once the user's interval is known.
 */
fun registerLibraryUpdateTask() {
    BGTaskScheduler.sharedScheduler.registerForTaskWithIdentifier(
        identifier = LIBRARY_UPDATE_TASK_ID,
        usingQueue = null,
    ) { task ->
        (task as? BGAppRefreshTask)?.let(::runLibraryUpdate)
    }
}

/**
 * Runs one background refresh, then hands the slot back to iOS.
 *
 * Three things have to happen in this order, and each is a way the chain breaks if skipped:
 *  - **Submit the successor first.** There is no periodic request on iOS, so a run that finishes
 *    without submitting the next one is the last one that ever happens. Doing it first means even a
 *    crash mid-update leaves the chain intact.
 *  - **Complete the task.** iOS watches how promptly an app finishes its refreshes and hands fewer
 *    slots to one that doesn't.
 *  - **Honour expiration.** iOS gives a refresh only seconds and kills the app if it overruns; the
 *    expiration handler cancels the work and reports the failure, which is far better received than
 *    being terminated.
 */
private fun runLibraryUpdate(task: BGAppRefreshTask) {
    val graph = appGraph
    val logger: Logger = graph.logger

    // No successor when the user has turned automatic updates off — this run is one iOS had already
    // accepted before the setting changed, and continuing the chain would quietly ignore them. The
    // update below still runs: it re-checks the interval itself and skips.
    nextInterval.period?.let { submit(after = it, logger = logger) }

    val job =
        graph.applicationScope.launch {
            graph.updateLibrary(UpdateLibrary.Trigger.Background)
            task.setTaskCompletedWithSuccess(true)
        }

    task.setExpirationHandler {
        logger.warn(TAG, "Background refresh ran out of time; cancelling")
        job.cancel()
        task.setTaskCompletedWithSuccess(false)
    }
}

private const val TAG = "LibraryUpdateTask"