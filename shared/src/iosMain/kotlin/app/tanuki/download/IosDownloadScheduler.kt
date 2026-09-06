package app.tanuki.download

import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.domain.common.service.info
import app.tanuki.core.domain.common.service.warn
import app.tanuki.core.domain.download.repository.DownloadRepository
import app.tanuki.core.domain.download.service.DownloadScheduler
import app.tanuki.core.domain.download.usecase.RunDownloadQueue
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import platform.UIKit.UIApplication
import platform.UIKit.UIBackgroundTaskIdentifier
import platform.UIKit.UIBackgroundTaskInvalid

/**
 * iOS [DownloadScheduler]: the queue runs in-process, with a grace period after the app leaves the
 * screen and a `BGProcessingTask` asking to be woken later.
 *
 * ### Why not a real background download
 *
 * Genuinely out-of-process downloading on iOS means `URLSession(background:)`, which is per-request and
 * lives outside the app — so it cannot use the Ktor client, and therefore cannot use the Cloudflare
 * interceptor, the shared cookie jar, or the host-owned `User-Agent` (see
 * `docs/design-downloads.md` §7 and the `source-user-agent-is-host-owned` note). A downloader that
 * bypasses all three is one that gets challenged on its own account and stops working on exactly the
 * sources this app exists to support. Foreground-plus-grace is the honest trade, and
 * [runsInBackground] is `false` so the queue screen can say so instead of letting the user discover it.
 *
 * ### The grace period
 *
 * `beginBackgroundTask` buys a short, system-determined window — tens of seconds, not minutes — after
 * the app is backgrounded. It is enough to finish the chapter in flight rather than abandoning it
 * mid-page, which is the difference between a resume that re-fetches two pages and one that re-fetches
 * twenty. The expiration handler ends the assertion; iOS kills apps that let one lapse.
 */
@OptIn(ExperimentalForeignApi::class)
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class IosDownloadScheduler(
    private val applicationScope: CoroutineScope,
    private val runDownloadQueue: RunDownloadQueue,
    private val downloadRepository: DownloadRepository,
    private val logger: Logger,
) : DownloadScheduler {
    /** Foreground plus a grace period is not "in the background", and must not be presented as it. */
    override val runsInBackground: Boolean = false

    override fun ensureRunning() {
        // Nothing here tracks whether a run is already going: the engine refuses a concurrent one, so a
        // redundant start is a coroutine that returns immediately — cheaper than the state it would take
        // to avoid, and without the race of keeping that state in step from whichever thread tapped.
        applicationScope.launch {
            val assertion = beginBackgroundAssertion()
            try {
                runDownloadQueue()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                // The engine isolates failures per chapter, so anything reaching here is the run itself
                // breaking. It must not take the application scope's other work down with it.
                logger.warn(TAG, "The download run ended unexpectedly", error)
            } finally {
                endBackgroundAssertion(assertion)
                // Asked for at the *end* of a run, and only when the run left something behind — a
                // queue the grace period ran out of, or one the user paused. Submitting after a run
                // that emptied the queue would ask iOS to wake the app for nothing, which is exactly
                // what spends the background budget that the runs which *do* have work then need.
                if (downloadRepository.nextQueued() != null) submitDownloadContinuation(logger)
            }
        }
    }

    /**
     * Asks iOS to keep the app alive briefly if it is backgrounded mid-run.
     *
     * Taken unconditionally rather than only when backgrounded: the app can be backgrounded at any
     * moment during a run, and there is no point at which asking afterwards would still be in time.
     */
    private fun beginBackgroundAssertion(): UIBackgroundTaskIdentifier =
        UIApplication.sharedApplication.beginBackgroundTaskWithName(ASSERTION_NAME) {
            logger.info(TAG, "The download grace period expired")
        }

    private fun endBackgroundAssertion(assertion: UIBackgroundTaskIdentifier) {
        if (assertion == UIBackgroundTaskInvalid) return
        UIApplication.sharedApplication.endBackgroundTask(assertion)
    }

    private companion object {
        const val TAG = "DownloadScheduler"

        /** Shown in os_log traces when the assertion is investigated; never seen by a user. */
        const val ASSERTION_NAME = "app.tanuki.downloads.grace"
    }
}