package app.tanuki.core.domain.settings.usecase

import app.tanuki.core.domain.settings.model.ThemePreference
import app.tanuki.core.domain.settings.repository.SettingsRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow

sealed interface ObserveThemePreference {
    operator fun invoke(): Flow<ThemePreference>

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val settingsRepository: SettingsRepository
    ) : ObserveThemePreference {
        override fun invoke(): Flow<ThemePreference> = settingsRepository.observeThemePreference()
    }
}