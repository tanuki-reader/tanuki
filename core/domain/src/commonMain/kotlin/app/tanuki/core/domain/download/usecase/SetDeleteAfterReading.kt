package app.tanuki.core.domain.download.usecase

import app.tanuki.core.domain.settings.model.DeleteAfterReading
import app.tanuki.core.domain.settings.repository.SettingsRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject

/**
 * Use case: choose how much read-chapter slack to keep on the device.
 *
 * Deliberately **not** retroactive. Turning the setting on does not sweep the chapters already read —
 * it changes what happens from now on. A setting that silently deleted a hundred existing downloads the
 * moment it was switched on is the one outcome the user cannot undo, and nothing in the row they tapped
 * would have suggested it.
 */
sealed interface SetDeleteAfterReading {
    suspend operator fun invoke(setting: DeleteAfterReading)

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val settingsRepository: SettingsRepository,
    ) : SetDeleteAfterReading {
        override suspend fun invoke(setting: DeleteAfterReading) =
            settingsRepository.setDeleteAfterReading(setting)
    }
}
