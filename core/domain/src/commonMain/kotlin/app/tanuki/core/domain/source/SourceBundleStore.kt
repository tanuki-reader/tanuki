package app.tanuki.core.domain.source

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