package app.tanuki.dev

import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.domain.common.service.warn
import app.tanuki.core.domain.source.SourceBundleStore
import app.tanuki.shared.resources.Res
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.ExperimentalResourceApi

/**
 * Temporary development convenience: copies the first-party `.wasm` bundles shipped inside the app
 * into the [SourceBundleStore], so a source is installed and loadable without a remote source repo.
 *
 * This exists purely to unblock the pipeline. The real flow — fetch a repo index, show available
 * extensions, download and verify on demand, check versions for updates — replaces it entirely, at
 * which point this class and the `composeResources/files/sources/` directory both go away.
 *
 * Idempotent by version, not by presence: a bundle is rewritten whenever [BUNDLED_VERSION] is bumped,
 * so rebuilding a source during development actually reaches the device instead of being shadowed by
 * a stale copy. Without that, every source change would need an app uninstall.
 */
@OptIn(ExperimentalResourceApi::class)
@Inject
class DevSourceInstaller(
    private val bundleStore: SourceBundleStore,
    private val dispatchers: DispatcherProvider,
    private val logger: Logger,
) {
    /**
     * Installs every bundled source that isn't already present at the current version. Failures are
     * swallowed per source — a missing or corrupt bundle must not stop the app from launching, and
     * the app is fully usable with no sources installed.
     */
    suspend fun installBundled() = withContext(dispatchers.io) {
        BUNDLED_SOURCES.forEach { sourceId ->
            runCatching {
                if (bundleStore.isInstalled(sourceId) && !shouldReinstall()) return@runCatching
                val bytes = Res.readBytes("files/sources/$sourceId.wasm")
                bundleStore.writeBundle(sourceId, bytes)
            }.onFailure { error ->
                logger.warn(TAG, "Could not install bundled source '$sourceId'", error)
            }
        }
    }

    /**
     * Whether to overwrite an already-installed bundle. Always true for now: during development the
     * `.wasm` changes far more often than this constant would, and rewriting a few KB on launch is
     * cheaper than debugging a stale bundle. Flip to a real version comparison when sources stabilise.
     */
    private fun shouldReinstall(): Boolean = true

    private companion object {
        const val TAG = "DevSourceInstaller"

        /**
         * Source ids to install, matching the crate directory names under `native/sources/` and the
         * `devSourceCrates` list in `shared/build.gradle.kts`. Each must correspond to a
         * `composeResources/files/sources/<id>.wasm`.
         */
        val BUNDLED_SOURCES = listOf("hello", "mangadex", "mangafire")
    }
}
