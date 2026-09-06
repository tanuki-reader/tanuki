package app.tanuki.core.domain.download.usecase

import app.tanuki.core.domain.download.model.DownloadSettings
import app.tanuki.core.domain.settings.repository.SettingsRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Use case: every download preference as one value.
 *
 * Combined here rather than in each caller because the four are never useful apart. The auto-download
 * rule alone spans two of them, and a caller left to `combine` them itself is a caller that can get the
 * pairing wrong — see [DownloadSettings.downloadsCategory], which is the only correct way to ask it.
 */
sealed interface ObserveDownloadSettings {
    operator fun invoke(): Flow<DownloadSettings>

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val settingsRepository: SettingsRepository,
    ) : ObserveDownloadSettings {
        override fun invoke(): Flow<DownloadSettings> =
            combine(
                settingsRepository.observeAutoDownloadMode(),
                settingsRepository.observeAutoDownloadCategories(),
                settingsRepository.observeDownloadOnWifiOnly(),
                settingsRepository.observeDeleteAfterReading(),
            ) { mode, categories, wifiOnly, deleteAfterReading ->
                DownloadSettings(
                    autoDownloadMode = mode,
                    autoDownloadCategories = categories,
                    onWifiOnly = wifiOnly,
                    deleteAfterReading = deleteAfterReading,
                )
            }
    }
}
