package app.tanuki.core.domain.settings.usecase

import app.tanuki.core.domain.settings.repository.SettingsRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow

/**
 * Observes whether the user has opted in to crash reporting.
 *
 * Two readers, deliberately: the settings toggle renders it, and app startup collects it to drive
 * `CrashReporter.setEnabled`. Because it is a [Flow] rather than a one-shot read, revoking consent takes
 * effect immediately — the reporter shuts down on the same emission that unticks the switch, instead of
 * waiting for a relaunch.
 */
sealed interface ObserveCrashReportingEnabled {
    operator fun invoke(): Flow<Boolean>

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val settingsRepository: SettingsRepository
    ) : ObserveCrashReportingEnabled {
        override fun invoke(): Flow<Boolean> = settingsRepository.observeCrashReportingEnabled()
    }
}