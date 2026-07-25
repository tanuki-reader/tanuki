package app.tanuki.core.domain.settings.usecase

import app.tanuki.core.domain.settings.model.ThemePreference
import app.tanuki.core.domain.settings.repository.SettingsRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject

sealed interface SetThemePreference {
    suspend operator fun invoke(preference: ThemePreference)

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val settingsRepository: SettingsRepository
    ) : SetThemePreference {
        override suspend fun invoke(preference: ThemePreference) =
            settingsRepository.setThemePreference(preference)
    }
}