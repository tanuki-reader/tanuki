package app.tanuki.core.domain.settings.usecase

import app.tanuki.core.domain.common.service.CrashReporter
import app.tanuki.core.domain.settings.repository.SettingsRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject

/**
 * Replaces this install's anonymous identifier with a new one, so reports sent from now on can't be tied
 * to the ones sent before. The user's answer to "stop recognising me" that doesn't require clearing data.
 *
 * The crash reporter is told as well: without it, a running reporter would keep the old id until the next
 * launch, and the reset would only look done.
 */
sealed interface ResetInstallId {
    suspend operator fun invoke()

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val settingsRepository: SettingsRepository,
        private val crashReporter: CrashReporter
    ) : ResetInstallId {
        override suspend fun invoke() {
            val newId = settingsRepository.resetInstallId()
            crashReporter.setUserId(newId)
        }
    }
}
