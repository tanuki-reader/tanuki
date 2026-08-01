package app.tanuki.core.domain.source.usecase

import app.tanuki.core.domain.settings.repository.SettingsRepository
import app.tanuki.core.domain.source.SourceBundleStore
import app.tanuki.core.domain.source.repository.SourceRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject

/**
 * Use case: uninstall a source — remove its `.wasm` bundle from the device.
 *
 * **The user's library is deliberately left alone.** The persisted [SourceRepository] row stays, and
 * with it every manga, chapter, library entry and history record that foreign-keys to it: deleting the
 * row would cascade and take all of that with it (see `MangaEntity`'s `ForeignKey.CASCADE`), turning an
 * "uninstall this extension" tap into silent, unrecoverable data loss. What the user gives up until they
 * install the source again is *fetching* — new chapters, pages, cover refreshes — not what they already
 * have. [InstallSource] restores full function, and because the row survived, it restores it against
 * the same local identity, so the library reconnects rather than duplicating.
 *
 * Also records the removal in [SettingsRepository.setSourceRemoved], which is what makes an uninstall
 * outlast the app: the bundled sources are seeded on launch, and without the record the next start
 * would quietly put the bundle back.
 *
 * Idempotent — uninstalling a source that isn't installed removes nothing and still records the intent.
 *
 * @see InstallSource for the inverse.
 */
sealed interface UninstallSource {
    suspend operator fun invoke(sourceId: String)

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val bundleStore: SourceBundleStore,
        private val settingsRepository: SettingsRepository
    ) : UninstallSource {
        override suspend fun invoke(sourceId: String) {
            // Order matters: the bundle goes first so that a failure to record the intent still leaves
            // the source uninstalled for this run, rather than a recorded removal with the bundle still
            // on disk (which reads as "installed" everywhere else in the app).
            bundleStore.removeBundle(sourceId)
            settingsRepository.setSourceRemoved(sourceId, removed = true)
        }
    }
}