package app.tanuki.dev

import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.domain.common.service.warn
import app.tanuki.core.domain.settings.repository.SettingsRepository
import app.tanuki.core.domain.source.SourceBundleStore
import app.tanuki.core.domain.source.SourceCatalog
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.withContext

/**
 * Temporary development convenience: copies the first-party `.wasm` bundles shipped inside the app
 * into the [SourceBundleStore], so a source is installed and loadable without a remote source repo.
 *
 * This exists purely to unblock the pipeline. The real flow — fetch a repo index, show available
 * extensions, download and verify on demand, check versions for updates — replaces it entirely, at
 * which point this class, [readBundledSource] and the bundled `.wasm` files all go away.
 *
 * It doubles as the app's [SourceCatalog], which is how "what can be installed?" has an answer at all
 * before that remote repo exists — and therefore how [app.tanuki.core.domain.source.usecase.InstallSource]
 * can put back a source the user uninstalled. When the real repo lands, the binding moves to it and
 * nothing above this class changes.
 *
 * Idempotent by version, not by presence: a bundle is rewritten whenever the bundled build changes,
 * so rebuilding a source during development actually reaches the device instead of being shadowed by
 * a stale copy. Without that, every source change would need an app uninstall.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DevSourceInstaller(
    private val bundleStore: SourceBundleStore,
    private val settingsRepository: SettingsRepository,
    private val dispatchers: DispatcherProvider,
    private val logger: Logger
) : SourceCatalog {
    override suspend fun availableIds(): List<String> = BUNDLED_SOURCES

    override suspend fun install(sourceId: String) {
        require(sourceId in BUNDLED_SOURCES) { "No bundled source '$sourceId' to install." }
        withContext(dispatchers.io) { writeBundled(sourceId) }
    }

    /**
     * Seeds every bundled source on launch, **except** the ones the user has uninstalled — otherwise
     * every start would silently undo their choice, and a source could only be removed for as long as
     * the app stayed open.
     *
     * Failures are swallowed per source: a missing or corrupt bundle must not stop the app from
     * launching, and the app is fully usable with no sources installed.
     */
    suspend fun installBundled() =
        withContext(dispatchers.io) {
            val removed = settingsRepository.getRemovedSourceIds()
            BUNDLED_SOURCES.forEach { sourceId ->
                runCatching {
                    if (sourceId in removed) return@runCatching
                    if (bundleStore.isInstalled(sourceId) && !shouldReinstall()) return@runCatching
                    writeBundled(sourceId)
                }.onFailure { error ->
                    logger.warn(TAG, "Could not install bundled source '$sourceId'", error)
                }
            }
        }

    /** Reads [sourceId]'s bundled `.wasm` out of the app's resources and into the store. */
    private suspend fun writeBundled(sourceId: String) {
        val bytes = readBundledSource(sourceId)
        bundleStore.writeBundle(sourceId, bytes)
    }

    /**
     * Whether to overwrite an already-installed bundle. Always true for now: during development the
     * `.wasm` changes far more often than any version constant would, and rewriting a few KB on launch
     * is cheaper than debugging a stale bundle. Flip to a real version comparison when sources stabilise.
     */
    private fun shouldReinstall(): Boolean = true

    private companion object {
        const val TAG = "DevSourceInstaller"

        /**
         * Source ids to install, matching the bundle ids in `devSourceBundles` in
         * `shared/build.gradle.kts`. Each must correspond to a
         * bundled `<id>.wasm` (see [readBundledSource]).
         *
         * A `-pt-br` suffix is a second *language build* of the same crate, not a second source — see
         * the `language` module in that crate. MangaDex and MangaFire ship both; the pt-BR-only sites
         * ship one.
         *
         * **`manhastro` is installed but not usable** — the site requires an account and Tanuki has no
         * source authentication yet (see that crate's module docs). It stays in the list so the work
         * isn't silently dropped; remove it from here if a non-working source in the list is worse than
         * a missing one.
         */
        val BUNDLED_SOURCES =
            listOf(
                "mangadex",
                "mangadex-pt-br",
                "mangafire",
                "mangafire-pt-br",
                "hipercool"
                // "manhastro",
            )
    }
}

/**
 * The bundled `.wasm` for [sourceId]: Android's `composeResources/files/sources/`, iOS's app bundle
 * `sources/` (copied there by `:shared:syncDevSourcesForXcode`). Throws if it isn't there.
 */
internal expect suspend fun readBundledSource(sourceId: String): ByteArray
