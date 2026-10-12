package app.tanuki.core.domain.source.usecase

import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.domain.common.service.warn
import app.tanuki.core.domain.source.SourceCatalog
import app.tanuki.core.domain.source.model.InstallProgress
import app.tanuki.core.domain.source.model.SourceRepoError
import app.tanuki.core.domain.source.model.SourceRepoException
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException

/**
 * Use case: install a source from the [SourceCatalog], writing its bundle to the device.
 *
 * The inverse of [UninstallSource], and the reason an uninstall is safe to offer: a source the user
 * removed can be brought back, and because the uninstall kept the source's persisted row, it comes back
 * against the same local identity — the library titles that were already there simply start fetching
 * again instead of being duplicated under a new source.
 *
 * Reports failure as a value, not a throw: what went wrong — offline, a repo that can't serve the bundle,
 * bytes that don't match the signed index — is not exceptional, and the screen names the failed step
 * next to a Retry.
 *
 * @return `null` when the source is now installed, otherwise the step that failed.
 */
sealed interface InstallSource {
    suspend operator fun invoke(
        sourceId: String,
        onProgress: (InstallProgress) -> Unit = {}
    ): SourceRepoError?

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val catalog: SourceCatalog,
        private val logger: Logger
    ) : InstallSource {
        override suspend fun invoke(
            sourceId: String,
            onProgress: (InstallProgress) -> Unit
        ): SourceRepoError? =
            try {
                catalog.install(sourceId, onProgress)
                null
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logger.warn(TAG, "Could not install source '$sourceId'", error)
                (error as? SourceRepoException)?.error ?: SourceRepoError.Unreachable
            }

        private companion object {
            const val TAG = "InstallSource"
        }
    }
}
