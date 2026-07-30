package app.tanuki.update

import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.domain.common.service.info
import app.tanuki.core.domain.common.service.warn
import app.tanuki.core.domain.settings.model.LibraryUpdateInterval
import app.tanuki.core.domain.update.service.LibraryUpdateScheduler
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.BackgroundTasks.BGAppRefreshTaskRequest
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.dateWithTimeIntervalSinceNow
import kotlin.time.Duration

/**
 * The identifier iOS knows this app's background refresh by.
 *
 * Must also appear in `Info.plist` under `BGTaskSchedulerPermittedIdentifiers`, or registering it
 * throws and submitting a request fails — iOS refuses to schedule work an app has not declared.
 */
internal const val LIBRARY_UPDATE_TASK_ID = "app.tanuki.library-update"

/**
 * iOS [LibraryUpdateScheduler], backed by a `BGAppRefreshTaskRequest`.
 *
 * Weaker than Android's WorkManager by design on Apple's side: [earliestBeginDate] is the *earliest*
 * iOS will consider running the task, not a schedule. The system decides when — or whether — based on
 * how often the user opens the app, battery, and connectivity, and a rarely-opened app may go days
 * without a refresh. That is why the launch-time catch-up in `App` exists: it is not a fallback for
 * this, it is the half that carries most users.
 *
 * Unlike WorkManager there is no periodic request type, so each run submits the next one (see
 * `registerLibraryUpdateTask`). This class only submits the *first*.
 */
@OptIn(ExperimentalForeignApi::class)
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class BgTaskLibraryUpdateScheduler(
    private val logger: Logger
) : LibraryUpdateScheduler {
    override fun schedule(interval: LibraryUpdateInterval) {
        // Each run has to submit its own successor (iOS has no periodic request), and the handler that
        // does it is a static launch handler — so the chosen interval is left where it can find it.
        rememberInterval(interval)

        val period = interval.period
        if (period == null) {
            BGTaskScheduler.sharedScheduler.cancelTaskRequestWithIdentifier(LIBRARY_UPDATE_TASK_ID)
            logger.info(TAG, "Automatic library updates are off")
            return
        }
        submit(period, logger)
    }

    private companion object {
        const val TAG = "LibraryUpdateScheduler"
    }
}

/**
 * Asks iOS to run the library update no sooner than [after] from now.
 *
 * Submitting replaces any pending request with the same identifier, so this is safe to call on every
 * launch and at the end of every run without queueing duplicates — which is also why the scheduler
 * doesn't need to track whether it has already submitted.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun submit(
    after: Duration,
    logger: Logger
) {
    val request =
        BGAppRefreshTaskRequest(LIBRARY_UPDATE_TASK_ID).apply {
            earliestBeginDate = NSDate.dateWithTimeIntervalSinceNow(after.inWholeSeconds.toDouble())
        }
    memScoped {
        val error = alloc<kotlinx.cinterop.ObjCObjectVar<NSError?>>()
        val submitted = BGTaskScheduler.sharedScheduler.submitTaskRequest(request, error.ptr)
        when {
            submitted -> logger.info("LibraryUpdateScheduler", "Next library update in ${after.inWholeHours}h")
            // The usual cause is a missing BGTaskSchedulerPermittedIdentifiers entry, or running on a
            // simulator, where background refresh is not delivered. Not fatal: the launch catch-up
            // still updates the library.
            else ->
                logger.warn(
                    "LibraryUpdateScheduler",
                    "Could not schedule background refresh: ${error.value?.localizedDescription}",
                )
        }
    }
}