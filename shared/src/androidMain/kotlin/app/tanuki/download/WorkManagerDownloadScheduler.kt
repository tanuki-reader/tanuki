package app.tanuki.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.domain.common.service.info
import app.tanuki.core.domain.download.service.DownloadScheduler
import app.tanuki.core.domain.settings.repository.SettingsRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Android [DownloadScheduler], backed by unique one-time [WorkManager] work.
 *
 * The queue is worked by [DownloadWorker] whether or not the app is open — there is no separate in-app
 * path. That is deliberate: two runners would mean two ways for the same rows to be picked up, and the
 * one guarantee this whole subsystem rests on is that exactly one thing works the queue at a time. The
 * engine's own mutex would catch a collision, but a design that cannot collide is better than one that
 * detects it.
 *
 * **One-time, not periodic.** A download queue is not a schedule: it exists because the user asked for
 * specific chapters, and it is finished when they are on the device. Every enqueue calls
 * [ensureRunning], and [ExistingWorkPolicy.KEEP] makes that free — an enqueue while a run is going is
 * dropped rather than queueing a second pass, because the run in flight re-reads the queue after every
 * chapter and will reach whatever was just added.
 *
 * `KEEP` is also what makes this safe to call at launch, on every tap of a download button, and from the
 * resume half of pausing, without any of them needing to know about the others.
 *
 * ### Reading the Wi-Fi-only setting
 *
 * The network constraint has to be decided *before* the request is enqueued, and [ensureRunning] is not
 * a suspend function — every caller is a tap handler or a launch hook. So the read happens on the
 * application scope and the enqueue follows it. That is not a delay anyone can perceive (WorkManager's
 * own enqueue is already asynchronous), and the alternative — caching the flag in a field kept current
 * by a collector — would decide a real constraint on a race, which is the shape phase 5 removed from the
 * engine for exactly this reason.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class WorkManagerDownloadScheduler(
    private val context: Context,
    private val applicationScope: CoroutineScope,
    private val settingsRepository: SettingsRepository,
    private val logger: Logger,
) : DownloadScheduler {
    /** WorkManager keeps the job alive across app death, and the worker promotes itself to foreground. */
    override val runsInBackground: Boolean = true

    override fun ensureRunning() {
        applicationScope.launch {
            val wifiOnly = settingsRepository.observeDownloadOnWifiOnly().first()
            val request =
                OneTimeWorkRequestBuilder<DownloadWorker>()
                    .setConstraints(
                        Constraints
                            .Builder()
                            // UNMETERED is WorkManager's "Wi-Fi or equivalent": the job simply waits
                            // rather than failing, which is what the setting means. The queue screen
                            // shows the chapters still queued, so a queue held back this way does not
                            // look like a queue that broke.
                            .setRequiredNetworkType(
                                if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED,
                            )
                            // A queue writes real bytes. Starting one on a device that is already out of
                            // room fails chapter after chapter and leaves part directories behind.
                            .setRequiresStorageNotLow(true)
                            .build(),
                    ).build()

            // REPLACE rather than KEEP when the constraint changed, so turning "only on Wi-Fi" off
            // releases a queue already waiting for Wi-Fi instead of leaving it stuck behind a request
            // enqueued under the old rule. KEEP everywhere else, so an ordinary enqueue during a run is
            // dropped rather than queueing a second pass.
            val policy = if (wifiOnly == lastEnqueuedWifiOnly) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE
            lastEnqueuedWifiOnly = wifiOnly

            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, policy, request)
            logger.info(TAG, "Download queue asked to run (wifiOnly=$wifiOnly)")
        }
    }

    /**
     * The constraint the last enqueue used, so a *change* to it can replace the pending request.
     *
     * Only ever read and written from the single coroutine above, so it needs no synchronisation. Its
     * initial `null` means the first enqueue after launch always replaces — which is right: a request
     * left pending by a previous process was enqueued under a setting this one has not seen.
     */
    private var lastEnqueuedWifiOnly: Boolean? = null

    private companion object {
        const val TAG = "DownloadScheduler"

        /**
         * The unique name the work is enqueued under. Stable across app versions — changing it would
         * orphan a job already on the device and let a second one run beside it.
         */
        const val WORK_NAME = "app.tanuki.downloads"
    }
}