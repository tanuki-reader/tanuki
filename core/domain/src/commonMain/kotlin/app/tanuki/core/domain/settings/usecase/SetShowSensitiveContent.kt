package app.tanuki.core.domain.settings.usecase

import app.tanuki.core.domain.settings.repository.SettingsRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject

/** Allow or hide sensitive (adult) content; see [ObserveShowSensitiveContent]. */
interface SetShowSensitiveContent {
    suspend operator fun invoke(show: Boolean)

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val settingsRepository: SettingsRepository
    ) : SetShowSensitiveContent {
        override suspend fun invoke(show: Boolean) = settingsRepository.setShowSensitiveContent(show)
    }
}
