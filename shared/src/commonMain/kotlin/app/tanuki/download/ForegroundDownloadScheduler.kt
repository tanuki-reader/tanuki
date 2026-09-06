package app.tanuki.download

import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.domain.common.service.warn
import app.tanuki.core.domain.download.service.DownloadScheduler
import app.tanuki.core.domain.download.usecase.DownloadManager
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Runs the download queue on the application scope — for as long as the app is alive, and no longer.
 *
 * This is the whole of the platform story for now, and it is honest about its limits: leaving the app
 * suspends the queue on iOS and leaves it at the OS's mercy on Android. Out-of-process execution — a
 * WorkManager job with a `dataSync` foreground service, a `BGProcessingTask` continuation, and the
 * notifications that go with them — is a phase of its own (`docs/design-downloads.md` §7), and it
 * replaces what is behind this interface without any caller changing: enqueueing already ends in
 * [ensureRunning] rather than in a coroutine of its own, which is why that indirection exists.
 *
 * Nothing here tracks whether a run is already going, because [DownloadManager] already refuses a
 * concurrent one. A redundant start is a coroutine that returns immediately — cheaper than the state it
 * would take to avoid it, and without the race of keeping that state in sync from whichever thread the
 * user happened to tap on.
 *
 * A queue left behind by an interrupted process is picked up rather than lost: rows stuck in
 * `Downloading` are requeued by the engine at the start of every run, and [App] asks for a run at launch.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ForegroundDownloadScheduler(
    private val applicationScope: CoroutineScope,
    private val downloadManager: DownloadManager,
    private val logger: Logger,
) : DownloadScheduler {
    override fun ensureRunning() {
        applicationScope.launch {
            try {
                downloadManager.run()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                // The engine isolates failures per chapter, so anything reaching here is the run itself
                // breaking. It must not take the application scope's other work down with it.
                logger.warn(TAG, "The download run ended unexpectedly", error)
            }
        }
    }

    private companion object {
        const val TAG = "DownloadScheduler"
    }
}