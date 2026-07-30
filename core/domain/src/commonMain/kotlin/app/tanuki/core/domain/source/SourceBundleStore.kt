package app.tanuki.core.domain.source

import kotlinx.coroutines.flow.Flow

/**
 * On-device storage for installed source `.wasm` bundles — the bytes the WASM runtime executes. The
 * port [app.tanuki.source.WasmSourceLoader] reads through to obtain a source's bytes
 * ([readBundle]) and to enumerate what's installed ([installedIds]); the install flow (remote repo
 * fetch, later) populates it via [writeBundle].
 *
 * Bundles are keyed by their **source-native id** (e.g. `"mangadex"`) — the same id the [SourceLoader]
 * addresses sources by, not a local `Uuid`. Implementations live in the `data` layer and must keep
 * platform/storage details (paths, file handles) behind this interface; only raw `ByteArray`s cross it.
 */
interface SourceBundleStore {
    /** Source-native ids of every currently installed bundle. Empty when none are installed. */
    suspend fun installedIds(): List<String>

    /**
     * [installedIds] as a stream: the current ids, then the new set every time a bundle is written or
     * removed. Never completes, and never fails for an absent store directory (that is simply empty).
     *
     * This is what lets a screen showing sources react to one being installed or uninstalled instead of
     * holding a list that silently goes stale. The signal is **in-process**: implementations report their
     * own mutations rather than watching the filesystem, which is complete because every install and
     * uninstall goes through this interface. A bundle changed behind the app's back (a debugger, another
     * process) is not observed — nothing in Tanuki does that.
     */
    fun observeInstalledIds(): Flow<List<String>>

    /** The `.wasm` bytes for [sourceId], or `null` if no bundle is installed under that id. */
    suspend fun readBundle(sourceId: String): ByteArray?

    /** Installs (or replaces) the bundle for [sourceId] with [bytes]. Written atomically. */
    suspend fun writeBundle(
        sourceId: String,
        bytes: ByteArray
    )

    /** Removes the bundle for [sourceId]; a no-op if it isn't installed. */
    suspend fun removeBundle(sourceId: String)

    /** Whether a bundle is installed under [sourceId]. */
    suspend fun isInstalled(sourceId: String): Boolean
}