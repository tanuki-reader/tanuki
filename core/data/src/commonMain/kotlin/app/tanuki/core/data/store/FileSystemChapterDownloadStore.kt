package app.tanuki.core.data.store

import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.download.model.DownloadManifest
import app.tanuki.core.domain.download.model.DownloadedPage
import app.tanuki.core.domain.download.service.ChapterDownloadPart
import app.tanuki.core.domain.download.service.ChapterDownloadStore
import app.tanuki.core.domain.download.service.StoredChapter
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.IOException
import okio.Path
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * [ChapterDownloadStore] backed by an okio [FileSystem], laid out as documented on the interface:
 * `<root>/<mangaId>/<chapterId>/` for a committed chapter and `<root>/<mangaId>/<chapterId>.part/`
 * for one in progress. [root] is a persistent, app-internal location supplied per platform (Android
 * `filesDir`, iOS Application Support with iCloud backup excluded) — never a cache directory, because
 * chapters the user asked to keep are user data and the OS must not reclaim them.
 *
 * All IO runs on [DispatcherProvider.io], so callers need not wrap anything. Every path is derived from
 * a [Uuid], so — unlike the source-bundle store, whose keys come from source metadata — no name
 * sanitizing or traversal check is needed: the only untrusted string that reaches disk is a page's file
 * extension, and that is normalized in [partPageName].
 */
class FileSystemChapterDownloadStore(
    private val fileSystem: FileSystem,
    private val root: Path,
    private val dispatchers: DispatcherProvider,
) : ChapterDownloadStore {

    override suspend fun manifest(
        mangaId: Uuid,
        chapterId: Uuid
    ): DownloadManifest? = withContext(dispatchers.io) { readManifest(chapterDir(mangaId, chapterId)) }

    override suspend fun pagePaths(
        mangaId: Uuid,
        chapterId: Uuid
    ): List<String>? = withContext(dispatchers.io) {
        val directory = chapterDir(mangaId, chapterId)
        readManifest(directory)?.pages?.map { (directory / it.file).toString() }
    }

    override suspend fun openPart(
        mangaId: Uuid,
        chapterId: Uuid
    ): ChapterDownloadPart = withContext(dispatchers.io) {
        val directory = partDir(mangaId, chapterId)
        fileSystem.createDirectories(directory)
        Part(mangaId, chapterId, directory)
    }

    override suspend fun commit(
        part: ChapterDownloadPart,
        expectedPageCount: Int
    ): DownloadManifest = withContext(dispatchers.io) {
        val directory = partDir(part.mangaId, part.chapterId)
        val pages = listPages(directory)

        // Committing a short chapter would mark it readable offline and then fail in the reader — the
        // one failure the .part/commit split exists to prevent. Checked here rather than trusted from
        // the caller because this is the last point at which the truth is still on disk.
        check(pages.size == expectedPageCount && pages.mapIndexed { i, page -> page.index == i }.all { it }) {
            "Refusing to commit ${part.chapterId}: expected $expectedPageCount contiguous pages, " +
                "found ${pages.size} at indices ${pages.map(DownloadedPage::index)}"
        }

        val manifest =
            DownloadManifest(
                version = DownloadManifest.CURRENT_VERSION,
                chapterId = part.chapterId,
                pages = pages,
                totalBytes = pages.sumOf(DownloadedPage::bytes),
                downloadedAt = Clock.System.now(),
            )
        fileSystem.write(directory / MANIFEST_FILE) {
            writeUtf8(json.encodeToString(manifest.toDto()))
        }

        // atomicMove replaces a file but not a non-empty directory, so any previous copy goes first.
        // The window this opens is harmless: the chapter reads as not-downloaded for an instant, which
        // is what it was a moment ago anyway.
        val target = chapterDir(part.mangaId, part.chapterId)
        fileSystem.deleteRecursively(target, mustExist = false)
        fileSystem.atomicMove(directory, target)
        manifest
    }

    override suspend fun delete(
        mangaId: Uuid,
        chapterId: Uuid
    ): Unit = withContext(dispatchers.io) {
        fileSystem.deleteRecursively(chapterDir(mangaId, chapterId), mustExist = false)
        fileSystem.deleteRecursively(partDir(mangaId, chapterId), mustExist = false)
        // Leave nothing behind for the sweep to puzzle over: an empty series directory is not a download.
        deleteIfEmpty(mangaDir(mangaId))
    }

    override suspend fun deleteManga(mangaId: Uuid): Unit = withContext(dispatchers.io) {
        fileSystem.deleteRecursively(mangaDir(mangaId), mustExist = false)
    }

    override suspend fun deleteAll(): Unit = withContext(dispatchers.io) {
        fileSystem.deleteRecursively(root, mustExist = false)
    }

    override suspend fun usedBytes(): Long = withContext(dispatchers.io) { sizeOf(root) }

    override suspend fun usedBytes(mangaId: Uuid): Long =
        withContext(dispatchers.io) { sizeOf(mangaDir(mangaId)) }

    override suspend fun storedChapters(): List<StoredChapter> = withContext(dispatchers.io) {
        list(root).flatMap { mangaDirectory ->
            val mangaId = mangaDirectory.name.toUuidOrNull() ?: return@flatMap emptyList()
            list(mangaDirectory).mapNotNull { chapterDirectory ->
                val name = chapterDirectory.name
                val isPart = name.endsWith(PART_SUFFIX)
                val chapterId = name.removeSuffix(PART_SUFFIX).toUuidOrNull() ?: return@mapNotNull null
                // Completeness is the manifest *existing*, not parsing: this runs at startup over every
                // downloaded chapter, and parsing them all would turn a directory listing into thousands
                // of file reads. A manifest that exists but is unreadable still fails closed — the read
                // path (readManifest) returns null and the chapter simply streams from its source.
                StoredChapter(
                    mangaId = mangaId,
                    chapterId = chapterId,
                    isComplete = !isPart && fileSystem.exists(chapterDirectory / MANIFEST_FILE),
                )
            }
        }
    }

    /** [ChapterDownloadPart] over a `<chapterId>.part` directory that [openPart] has already created. */
    private inner class Part(
        override val mangaId: Uuid,
        override val chapterId: Uuid,
        private val directory: Path,
    ) : ChapterDownloadPart {
        override suspend fun existingPages(): List<DownloadedPage> =
            withContext(dispatchers.io) { listPages(directory) }

        override suspend fun writePage(
            index: Int,
            extension: String,
            bytes: ByteArray
        ): DownloadedPage = withContext(dispatchers.io) {
            fileSystem.createDirectories(directory)
            // A retry can bring the same page back under a different extension (a source switching
            // formats, or a placeholder replaced by the real image), so clear the index rather than
            // assuming the name matches.
            list(directory).filter { it.name.pageIndex() == index }.forEach {
                fileSystem.delete(it, mustExist = false)
            }

            val name = partPageName(index, extension)
            fileSystem.write(directory / name) { write(bytes) }
            DownloadedPage(index = index, file = name, bytes = bytes.size.toLong())
        }

        override suspend fun discard(): Unit = withContext(dispatchers.io) {
            fileSystem.deleteRecursively(directory, mustExist = false)
            deleteIfEmpty(mangaDir(mangaId))
        }
    }

    // --- paths ----------------------------------------------------------------------------------

    private fun mangaDir(mangaId: Uuid): Path = root / mangaId.toString()

    private fun chapterDir(
        mangaId: Uuid,
        chapterId: Uuid
    ): Path = mangaDir(mangaId) / chapterId.toString()

    private fun partDir(
        mangaId: Uuid,
        chapterId: Uuid
    ): Path = mangaDir(mangaId) / "$chapterId$PART_SUFFIX"

    // --- filesystem helpers ---------------------------------------------------------------------

    /** [FileSystem.list], but an absent directory is an empty one rather than a failure. */
    private fun list(directory: Path): List<Path> =
        if (fileSystem.exists(directory)) fileSystem.list(directory) else emptyList()

    private fun readManifest(directory: Path): DownloadManifest? {
        val file = directory / MANIFEST_FILE
        if (!fileSystem.exists(file)) return null
        return try {
            json.decodeFromString<DownloadManifestDto>(fileSystem.read(file) { readUtf8() }).toDomain()
        } catch (_: IOException) {
            // A manifest that cannot be read or parsed means "not downloaded", never a crash: the
            // reader falls back to the source and the next sweep reclaims the directory.
            null
        } catch (_: IllegalArgumentException) {
            // kotlinx.serialization surfaces malformed JSON and impossible field values as this.
            null
        }
    }

    /** The pages currently in a directory, index order, ignoring anything that isn't page-shaped. */
    private fun listPages(directory: Path): List<DownloadedPage> =
        list(directory)
            .mapNotNull { path ->
                val index = path.name.pageIndex() ?: return@mapNotNull null
                val size = fileSystem.metadataOrNull(path)?.size ?: return@mapNotNull null
                DownloadedPage(index = index, file = path.name, bytes = size)
            }
            .sortedBy(DownloadedPage::index)

    private fun sizeOf(directory: Path): Long {
        if (!fileSystem.exists(directory)) return 0L
        return fileSystem
            .listRecursively(directory)
            .sumOf { fileSystem.metadataOrNull(it)?.size ?: 0L }
    }

    private fun deleteIfEmpty(directory: Path) {
        if (fileSystem.exists(directory) && list(directory).isEmpty()) {
            fileSystem.delete(directory, mustExist = false)
        }
    }

    private companion object {
        const val MANIFEST_FILE = "manifest.json"
        const val PART_SUFFIX = ".part"

        /**
         * Zero padding for page file names. Purely cosmetic — page order comes from the manifest, never
         * from sorting names — but it keeps a directory readable when someone does look at one, and
         * three digits covers every real chapter without truncating the ones that exceed it.
         */
        const val INDEX_DIGITS = 3

        /** Extension used when a source's content type gives nothing usable. */
        const val DEFAULT_EXTENSION = "img"

        val json = Json { ignoreUnknownKeys = true }

        /** `007.jpg`. The extension is normalized: it becomes part of a file name. */
        fun partPageName(
            index: Int,
            extension: String
        ): String {
            val safe =
                extension
                    .trim()
                    .removePrefix(".")
                    .lowercase()
                    .filter { it in 'a'..'z' || it in '0'..'9' }
                    .take(8)
                    .ifEmpty { DEFAULT_EXTENSION }
            return "${index.toString().padStart(INDEX_DIGITS, '0')}.$safe"
        }

        /** The page index a file name encodes, or `null` for `manifest.json` and anything else stray. */
        fun String.pageIndex(): Int? = substringBefore('.').toIntOrNull()

        fun String.toUuidOrNull(): Uuid? =
            try {
                Uuid.parse(this)
            } catch (_: IllegalArgumentException) {
                null
            }
    }
}
