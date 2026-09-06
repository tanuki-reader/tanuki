package app.tanuki.core.domain.download.usecase

import app.tanuki.core.domain.settings.repository.SettingsRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow

/**
 * Use case: observe whether the download queue is paused — what the queue screen's pause/resume control
 * reflects, and what the engine checks between chapters.
 *
 * It lives beside the downloads rather than beside the other settings use cases because pausing is a
 * *download* concept that happens to be stored as a preference: nothing in the settings surface reads it,
 * and both of its readers are here. [SetDownloadsPaused] is the write half, and the asymmetry between
 * them — resuming has to start the engine — is the reason neither is a bare repository call.
 */
sealed interface ObserveDownloadsPaused {
    operator fun invoke(): Flow<Boolean>

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val settingsRepository: SettingsRepository,
    ) : ObserveDownloadsPaused {
        override fun invoke(): Flow<Boolean> = settingsRepository.observeDownloadsPaused()
    }
}