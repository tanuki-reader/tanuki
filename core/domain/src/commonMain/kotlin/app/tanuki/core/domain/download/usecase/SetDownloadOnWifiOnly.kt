package app.tanuki.core.domain.download.usecase

import app.tanuki.core.domain.download.service.DownloadScheduler
import app.tanuki.core.domain.settings.repository.SettingsRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject

/**
 * Use case: hold the queue for an unmetered connection, or let it run on anything.
 *
 * Ends in [DownloadScheduler.ensureRunning] like the resume half of pausing, and for the same reason:
 * the constraint is applied when work is *enqueued*, so a queue already waiting under the old rule keeps
 * waiting under it until something re-enqueues. Turning the restriction **off** while connected to
 * mobile data would otherwise appear to do nothing at all.
 *
 * Re-running when the restriction is turned *on* is harmless — the run is enqueued with the new
 * constraint and simply waits for Wi-Fi.
 */
sealed interface SetDownloadOnWifiOnly {
    suspend operator fun invoke(wifiOnly: Boolean)

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val settingsRepository: SettingsRepository,
        private val scheduler: DownloadScheduler,
    ) : SetDownloadOnWifiOnly {
        override suspend fun invoke(wifiOnly: Boolean) {
            settingsRepository.setDownloadOnWifiOnly(wifiOnly)
            scheduler.ensureRunning()
        }
    }
}
