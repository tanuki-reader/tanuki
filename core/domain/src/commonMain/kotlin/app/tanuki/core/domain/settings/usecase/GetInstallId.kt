package app.tanuki.core.domain.settings.usecase

import app.tanuki.core.domain.settings.repository.SettingsRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject

/**
 * Returns this install's anonymous identifier, creating it on first use.
 *
 * Crash reporting's only reader: it sets the id as the report's user, so a crash-free rate and a
 * "users affected" count mean something. See [SettingsRepository.getOrCreateInstallId] for what the id
 * is — and, as importantly, what it is not.
 */
sealed interface GetInstallId {
    suspend operator fun invoke(): String

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val settingsRepository: SettingsRepository
    ) : GetInstallId {
        override suspend fun invoke(): String = settingsRepository.getOrCreateInstallId()
    }
}
