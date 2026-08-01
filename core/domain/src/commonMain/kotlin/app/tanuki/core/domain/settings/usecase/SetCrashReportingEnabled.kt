package app.tanuki.core.domain.settings.usecase

import app.tanuki.core.domain.settings.repository.SettingsRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject

/**
 * Records the user's crash-reporting choice.
 *
 * Only persists it. Starting or stopping the reporter is left to whoever collects
 * [ObserveCrashReportingEnabled], so the stored preference stays the single source of truth and the
 * reporter cannot end up in a state the preference disagrees with — including after a relaunch, where
 * nothing calls this at all.
 */
sealed interface SetCrashReportingEnabled {
    suspend operator fun invoke(enabled: Boolean)

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val settingsRepository: SettingsRepository
    ) : SetCrashReportingEnabled {
        override suspend fun invoke(enabled: Boolean) = settingsRepository.setCrashReportingEnabled(enabled)
    }
}