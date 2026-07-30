package app.tanuki.core.source.store

import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.source.SourceBundleStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path

/**
 * [SourceBundleStore] backed by an okio [FileSystem] — one `<sourceId>.wasm` file per installed bundle
 * under [directory] (a persistent, app-internal location supplied per platform: Android `filesDir`,
 * iOS Application Support). Disk writes are atomic (write-then-move); all IO runs on
 * [DispatcherProvider.io].
 *
 * Source ids become file names directly, so they are validated against [SAFE_ID] first — this both
 * rejects path-traversal (`..`, `/`) from untrusted source metadata and keeps the directory readable.
 */
class FileSystemSourceBundleStore(
    private val fileSystem: FileSystem,
    private val directory: Path,
    private val dispatchers: DispatcherProvider,
) : SourceBundleStore {

    /**
     * Bumped by every mutation, and the only thing [observeInstalledIds] reacts to. A counter rather than
     * the id list itself so the re-read stays on [DispatcherProvider.io] — and because the store is the
     * sole writer to [directory], an in-process signal misses nothing.
     */
    private val revision = MutableStateFlow(0)

    override suspend fun installedIds(): List<String> = withContext(dispatchers.io) {
        if (!fileSystem.exists(directory)) {
            emptyList()
        } else {
            fileSystem.list(directory)
                .filter { it.name.endsWith(WASM_EXTENSION) }
                .map { it.name.removeSuffix(WASM_EXTENSION) }
        }
    }

    override fun observeInstalledIds(): Flow<List<String>> =
        revision
            .map { installedIds() }
            // A write that replaces an existing bundle leaves the id set unchanged, and the dev installer
            // rewrites every bundled source on launch — without this, each of those would push an
            // identical list to every collector and re-enumerate the whole runtime downstream.
            .distinctUntilChanged()

    override suspend fun readBundle(sourceId: String): ByteArray? = withContext(dispatchers.io) {
        val path = bundlePath(sourceId)
        if (fileSystem.exists(path)) fileSystem.read(path) { readByteArray() } else null
    }

    override suspend fun writeBundle(sourceId: String, bytes: ByteArray): Unit = withContext(dispatchers.io) {
        val path = bundlePath(sourceId)
        fileSystem.createDirectories(directory)
        // Write to a temp sibling then atomically move, so a crash mid-write can't leave a partial .wasm.
        val tmp = directory / "${path.name}.tmp"
        fileSystem.write(tmp) { write(bytes) }
        fileSystem.atomicMove(tmp, path)
        revision.value++
    }

    override suspend fun removeBundle(sourceId: String): Unit = withContext(dispatchers.io) {
        fileSystem.delete(bundlePath(sourceId), mustExist = false)
        revision.value++
    }

    override suspend fun isInstalled(sourceId: String): Boolean = withContext(dispatchers.io) {
        fileSystem.exists(bundlePath(sourceId))
    }

    private fun bundlePath(sourceId: String): Path {
        require(sourceId.matches(SAFE_ID)) { "Unsafe source id for a bundle file name: '$sourceId'" }
        return directory / "$sourceId$WASM_EXTENSION"
    }

    private companion object {
        const val WASM_EXTENSION = ".wasm"

        /**
         * Allowed source-id characters. Excludes `/` and any all-dots name (`.`/`..`), so a hostile id
         * can't escape [directory]. Source ids are simple slugs (e.g. `mangadex`, `io.github.author`).
         */
        val SAFE_ID = Regex("(?!\\.+$)[A-Za-z0-9._-]+")
    }
}