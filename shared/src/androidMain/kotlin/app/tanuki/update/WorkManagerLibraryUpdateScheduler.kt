package app.tanuki.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.domain.common.service.info
import app.tanuki.core.domain.settings.model.LibraryUpdateInterval
import app.tanuki.core.domain.update.service.LibraryUpdateScheduler
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.util.concurrent.TimeUnit

/**
 * Android [LibraryUpdateScheduler], backed by a unique periodic [WorkManager] request.
 *
 * WorkManager treats a period as "at most once per period, when the constraints hold" — it batches work
 * across the device and will happily run late. That is the intended trade: a library check is worth
 * nothing if it costs the user battery at an inconvenient moment.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class WorkManagerLibraryUpdateScheduler(
    private val context: Context,
    private val logger: Logger
) : LibraryUpdateScheduler {
    override fun schedule(interval: LibraryUpdateInterval) {
        val workManager = WorkManager.getInstance(context)
        val period = interval.period
        if (period == null) {
            workManager.cancelUniqueWork(WORK_NAME)
            logger.info(TAG, "Automatic library updates are off")
            return
        }

        val request =
            PeriodicWorkRequestBuilder<LibraryUpdateWorker>(period.inWholeMinutes, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints
                        .Builder()
                        // A refresh is entirely network work; running it offline just burns a period
                        // failing every series. Not UNMETERED — chapter feeds are small JSON, and
                        // demanding Wi-Fi would leave a mobile-only user with no updates at all.
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                ).build()

        // UPDATE, not REPLACE: an unchanged request is left running with its timer intact, so an app
        // that is opened several times a day still reaches the end of a daily period. A changed period
        // is applied without the user losing their place in the current one.
        workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        logger.info(TAG, "Library updates scheduled every ${period.inWholeHours}h")
    }

    private companion object {
        const val TAG = "LibraryUpdateScheduler"

        /**
         * The unique name the periodic work is enqueued under. Stable across app versions — changing it
         * would orphan the schedule already on the device and quietly double it up.
         */
        const val WORK_NAME = "app.tanuki.library-update"
    }
}