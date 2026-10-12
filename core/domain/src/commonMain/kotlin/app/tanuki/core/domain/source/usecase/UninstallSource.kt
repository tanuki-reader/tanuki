package app.tanuki.core.domain.source.usecase

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
 * Idempotent — uninstalling a source that isn't installed removes nothing.
 *
 * @see InstallSource for the inverse.
 */
sealed interface UninstallSource {
    suspend operator fun invoke(sourceId: String)

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val bundleStore: SourceBundleStore
    ) : UninstallSource {
        override suspend fun invoke(sourceId: String) = bundleStore.removeBundle(sourceId)
    }
}