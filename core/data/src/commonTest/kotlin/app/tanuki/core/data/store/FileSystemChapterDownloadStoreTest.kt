package app.tanuki.core.data.store

import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.download.model.DownloadedPage
import app.tanuki.core.domain.download.service.StoredChapter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * The store is where "the user can read this on a plane" is actually decided, and every property worth
 * having is about what survives an interruption: a chapter is readable only once every page is on disk,
 * a half-finished one is resumable rather than broken, and neither state can be mistaken for the other.
 *
 * Run against okio's `FakeFileSystem`, so the interesting cases (a crash between the last page and the
 * manifest, a directory left by a cancelled download, a page rewritten in a different format) are set
 * up directly instead of being waited for.
 */
class FileSystemChapterDownloadStoreTest {
    private val fileSystem = FakeFileSystem()
    private val root: Path = "/downloads".toPath()
    private val store =
        FileSystemChapterDownloadStore(
            fileSystem = fileSystem,
            root = root,
            dispatchers = StoreDispatchers(UnconfinedTestDispatcher()),
        )

    private val mangaId = Uuid.parse("00000000-0000-7000-8000-000000000001")
    private val chapterId = Uuid.parse("00000000-0000-7000-8000-000000000002")

    @Test
    fun `a committed chapter reports its pages in reading order`() = runTest {
        downloadChapter(pages = 3)

        val paths = assertNotNull(store.pagePaths(mangaId, chapterId))
        assertEquals(
            listOf("000.jpg", "001.jpg", "002.jpg"),
            paths.map { it.substringAfterLast('/') },
        )
        // Absolute, and under the committed directory rather than the part it was built in.
        assertTrue(paths.all { it.startsWith("$root/$mangaId/$chapterId/") }, "not committed paths: $paths")
    }

    @Test
    fun `page order comes from the manifest rather than from sorting file names`() = runTest {
        val part = store.openPart(mangaId, chapterId)
        // Written out of order and with mixed formats — a real queue fetches pages concurrently.
        part.writePage(2, "png", bytes(30))
        part.writePage(0, "jpg", bytes(10))
        part.writePage(1, "webp", bytes(20))
        val manifest = store.commit(part, expectedPageCount = 3)

        assertEquals(listOf(0, 1, 2), manifest.pages.map(DownloadedPage::index))
        assertEquals(listOf("000.jpg", "001.webp", "002.png"), manifest.pages.map(DownloadedPage::file))
        assertEquals(60L, manifest.totalBytes)
        assertEquals(chapterId, manifest.chapterId)
    }

    @Test
    fun `an uncommitted part is not a download`() = runTest {
        val part = store.openPart(mangaId, chapterId)
        part.writePage(0, "jpg", bytes(10))
        part.writePage(1, "jpg", bytes(10))

        assertNull(store.manifest(mangaId, chapterId))
        assertNull(store.pagePaths(mangaId, chapterId))
        assertEquals(
            listOf(StoredChapter(mangaId, chapterId, isComplete = false)),
            store.storedChapters(),
        )
    }

    @Test
    fun `reopening a part keeps what an interrupted attempt already fetched`() = runTest {
        val interrupted = store.openPart(mangaId, chapterId)
        interrupted.writePage(0, "jpg", bytes(10))
        interrupted.writePage(1, "jpg", bytes(20))

        val resumed = store.openPart(mangaId, chapterId)
        assertEquals(
            listOf(
                DownloadedPage(0, "000.jpg", 10L),
                DownloadedPage(1, "001.jpg", 20L),
            ),
            resumed.existingPages(),
        )

        resumed.writePage(2, "jpg", bytes(30))
        val manifest = store.commit(resumed, expectedPageCount = 3)
        assertEquals(60L, manifest.totalBytes)
    }

    @Test
    fun `committing a short chapter is refused`() = runTest {
        val part = store.openPart(mangaId, chapterId)
        part.writePage(0, "jpg", bytes(10))

        assertFailsWith<IllegalStateException> { store.commit(part, expectedPageCount = 3) }
        // And nothing was published: the chapter is still not downloaded.
        assertNull(store.pagePaths(mangaId, chapterId))
    }

    @Test
    fun `committing a chapter with a hole is refused`() = runTest {
        val part = store.openPart(mangaId, chapterId)
        part.writePage(0, "jpg", bytes(10))
        part.writePage(2, "jpg", bytes(10))

        assertFailsWith<IllegalStateException> { store.commit(part, expectedPageCount = 2) }
    }

    @Test
    fun `rewriting a page in another format replaces it rather than duplicating the index`() = runTest {
        val part = store.openPart(mangaId, chapterId)
        part.writePage(0, "jpg", bytes(10))
        part.writePage(0, "png", bytes(99))

        assertEquals(listOf(DownloadedPage(0, "000.png", 99L)), part.existingPages())
    }

    @Test
    fun `recommitting replaces the previous copy`() = runTest {
        downloadChapter(pages = 3)
        downloadChapter(pages = 1)

        val paths = assertNotNull(store.pagePaths(mangaId, chapterId))
        assertEquals(1, paths.size)
        // The pages of the old copy are gone, not orphaned inside the new directory.
        assertEquals(2, fileSystem.list(root / "$mangaId" / "$chapterId").size)
    }

    @Test
    fun `a manifest that cannot be parsed reads as not downloaded`() = runTest {
        downloadChapter(pages = 2)
        fileSystem.write(root / "$mangaId" / "$chapterId" / "manifest.json") { writeUtf8("{ nonsense") }

        assertNull(store.manifest(mangaId, chapterId))
        assertNull(store.pagePaths(mangaId, chapterId))
    }

    @Test
    fun `discarding a part leaves nothing behind`() = runTest {
        val part = store.openPart(mangaId, chapterId)
        part.writePage(0, "jpg", bytes(10))
        part.discard()

        assertEquals(emptyList(), store.storedChapters())
        assertEquals(0L, store.usedBytes())
        assertFalse(fileSystem.exists(root / "$mangaId"), "the empty series directory was left behind")
    }

    @Test
    fun `deleting a chapter takes its part with it`() = runTest {
        downloadChapter(pages = 2)
        val otherChapter = Uuid.parse("00000000-0000-7000-8000-000000000003")
        store.openPart(mangaId, otherChapter).writePage(0, "jpg", bytes(10))

        store.delete(mangaId, chapterId)
        store.delete(mangaId, otherChapter)

        assertEquals(emptyList(), store.storedChapters())
    }

    @Test
    fun `deleting a series removes every chapter it holds and nothing else`() = runTest {
        downloadChapter(pages = 1)
        val otherManga = Uuid.parse("00000000-0000-7000-8000-00000000000a")
        downloadChapter(pages = 1, mangaId = otherManga)

        store.deleteManga(mangaId)

        assertEquals(listOf(otherManga), store.storedChapters().map(StoredChapter::mangaId))
    }

    @Test
    fun `used bytes is reported for the whole store and per series`() = runTest {
        downloadChapter(pages = 2, pageBytes = 100)
        val otherManga = Uuid.parse("00000000-0000-7000-8000-00000000000a")
        downloadChapter(pages = 1, pageBytes = 50, mangaId = otherManga)

        // Pages only, ignoring the manifests, would be 250; the manifests are real bytes on the user's
        // device, so the figure in settings has to include them.
        assertTrue(store.usedBytes() > 250L, "manifests should count toward the total")
        assertTrue(store.usedBytes(mangaId) > 200L)
        assertEquals(store.usedBytes(), store.usedBytes(mangaId) + store.usedBytes(otherManga))
    }

    @Test
    fun `an empty store reports nothing rather than failing`() = runTest {
        assertEquals(emptyList(), store.storedChapters())
        assertEquals(0L, store.usedBytes())
        assertEquals(0L, store.usedBytes(mangaId))
        assertNull(store.manifest(mangaId, chapterId))
    }

    @Test
    fun `directories that are not chapter downloads are ignored`() = runTest {
        downloadChapter(pages = 1)
        fileSystem.createDirectories(root / "not-a-uuid" / "$chapterId")
        fileSystem.createDirectories(root / "$mangaId" / "scratch")

        assertEquals(
            listOf(StoredChapter(mangaId, chapterId, isComplete = true)),
            store.storedChapters(),
        )
    }

    @Test
    fun `deleting everything empties the store`() = runTest {
        downloadChapter(pages = 2)
        downloadChapter(pages = 2, mangaId = Uuid.parse("00000000-0000-7000-8000-00000000000a"))

        store.deleteAll()

        assertEquals(emptyList(), store.storedChapters())
        assertEquals(0L, store.usedBytes())
    }

    /** Downloads a chapter end to end, the way the engine will. */
    private suspend fun downloadChapter(
        pages: Int,
        pageBytes: Int = 10,
        mangaId: Uuid = this.mangaId,
        chapterId: Uuid = this.chapterId,
    ) {
        val part = store.openPart(mangaId, chapterId)
        repeat(pages) { index -> part.writePage(index, "jpg", bytes(pageBytes)) }
        store.commit(part, expectedPageCount = pages)
    }

    private fun bytes(size: Int) = ByteArray(size) { it.toByte() }
}

/** Runs every dispatcher on the test scheduler so the store's `withContext` hops stay deterministic. */
private class StoreDispatchers(
    private val dispatcher: CoroutineDispatcher,
) : DispatcherProvider {
    override val default: CoroutineDispatcher = dispatcher
    override val io: CoroutineDispatcher = dispatcher
    override val main: CoroutineDispatcher = dispatcher
}
