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
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

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
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class WorkManagerDownloadScheduler(
    private val context: Context,
    private val logger: Logger,
) : DownloadScheduler {
    /** WorkManager keeps the job alive across app death, and the worker promotes itself to foreground. */
    override val runsInBackground: Boolean = true

    override fun ensureRunning() {
        val request =
            OneTimeWorkRequestBuilder<DownloadWorker>()
                .setConstraints(
                    Constraints
                        .Builder()
                        // CONNECTED, not UNMETERED: "only on Wi-Fi" is a user setting that does not
                        // exist yet (phase 7), and imposing it here would silently strand every
                        // mobile-only user's queue with nothing on screen explaining why.
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        // A queue writes real bytes. Starting one on a device that is already out of
                        // room fails chapter after chapter and leaves part directories behind.
                        .setRequiresStorageNotLow(true)
                        .build(),
                ).build()

        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        logger.info(TAG, "Download queue asked to run")
    }

    private companion object {
        const val TAG = "DownloadScheduler"

        /**
         * The unique name the work is enqueued under. Stable across app versions — changing it would
         * orphan a job already on the device and let a second one run beside it.
         */
        const val WORK_NAME = "app.tanuki.downloads"
    }
}