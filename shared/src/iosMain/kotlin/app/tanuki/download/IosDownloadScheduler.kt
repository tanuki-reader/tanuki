package app.tanuki.download

import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.domain.common.service.info
import app.tanuki.core.domain.common.service.warn
import app.tanuki.core.domain.download.repository.DownloadRepository
import app.tanuki.core.domain.download.service.DownloadScheduler
import app.tanuki.core.domain.download.model.DownloadHold
import app.tanuki.core.domain.download.usecase.ObserveDownloadHold
import app.tanuki.core.domain.download.usecase.RunDownloadQueue
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
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
 *
 * ### Getting going again after a hold
 *
 * The engine stops itself when [ObserveDownloadHold] says so — the user paused, or "only on Wi-Fi" is on
 * over cellular — but nothing restarts it, because a stopped run is simply a run that ended. Android
 * gets this for free: its work is enqueued with an `UNMETERED` constraint and WorkManager re-runs the job
 * when the constraint is met again. iOS has no such machinery, so this watches the hold itself and asks
 * for a run the moment it clears. Without it, plugging into Wi-Fi would do nothing until the user next
 * opened the app and something happened to enqueue a chapter.
 */
@OptIn(ExperimentalForeignApi::class)
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class IosDownloadScheduler(
    private val applicationScope: CoroutineScope,
    private val runDownloadQueue: RunDownloadQueue,
    private val downloadRepository: DownloadRepository,
    observeDownloadHold: ObserveDownloadHold,
    private val logger: Logger,
) : DownloadScheduler {
    /** Foreground plus a grace period is not "in the background", and must not be presented as it. */
    override val runsInBackground: Boolean = false

    init {
        applicationScope.launch {
            // `drop(1)` so construction does not trigger a run of its own: the launch-time
            // `ensureRunning()` in `App` already covers starting up, and running twice at launch is
            // wasted work the engine's mutex would only have to reject.
            observeDownloadHold()
                .map { hold -> hold.isRunnable }
                .distinctUntilChanged()
                .drop(1)
                .filter { runnable -> runnable }
                .collect {
                    logger.info(TAG, "The hold cleared; asking the queue to run")
                    ensureRunning()
                }
        }
    }

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