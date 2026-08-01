package app.tanuki.core.domain.source.usecase

import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.domain.common.service.warn
import app.tanuki.core.domain.settings.repository.SettingsRepository
import app.tanuki.core.domain.source.SourceCatalog
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject

/**
 * Use case: install a source from the [SourceCatalog], writing its bundle to the device.
 *
 * The inverse of [UninstallSource], and the reason an uninstall is safe to offer: a source the user
 * removed can be brought back, and because the uninstall kept the source's persisted row, it comes back
 * against the same local identity — the library titles that were already there simply start fetching
 * again instead of being duplicated under a new source.
 *
 * Clearing the removal record is part of installing, not a separate step: leaving it set would have the
 * launch-time seeding treat a source the user just installed as one they had removed.
 *
 * Returns whether the install succeeded rather than throwing. The failure a caller actually hits —
 * a bundle that can't be read, a catalog that can't reach it — is not exceptional and not the UI's to
 * diagnose; it just means the row stays where it was and the screen says so.
 *
 * @return `true` when the source is now installed, `false` when it could not be.
 */
sealed interface InstallSource {
    suspend operator fun invoke(sourceId: String): Boolean

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val catalog: SourceCatalog,
        private val settingsRepository: SettingsRepository,
        private val logger: Logger
    ) : InstallSource {
        override suspend fun invoke(sourceId: String): Boolean =
            runCatching {
                catalog.install(sourceId)
                settingsRepository.setSourceRemoved(sourceId, removed = false)
            }.onFailure { error ->
                logger.warn(TAG, "Could not install source '$sourceId'", error)
            }.isSuccess

        private companion object {
            const val TAG = "InstallSource"
        }
    }
}