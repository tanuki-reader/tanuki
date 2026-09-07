package app.tanuki.core.domain.download.usecase

import app.tanuki.core.domain.download.model.ChapterDownload
import app.tanuki.core.domain.download.model.DownloadError
import app.tanuki.core.domain.download.service.StoredChapter
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests the reconciliation sweep, whose four rules are each a drift the app cannot otherwise see.
 *
 * Every case here is built by breaking one side on purpose — deleting the files under a record, or the
 * record under the files — because that is exactly what the world does to this subsystem: a user clears
 * app storage from the OS, a sync prunes a chapter and the foreign key takes only its row, a run is
 * killed mid-page. The two "leave it alone" cases matter most: they are what makes the sweep safe to run
 * beside a live queue, and getting them wrong would delete a user's resumable download rather than
 * leaking a directory.
 */
class VerifyDownloadsTest {
    @Test
    fun `a downloaded record whose files are gone loses its record`() =
        runTest {
            val world = world()
            val chapter = world.chapters[0]
            world.queue(chapter)
            world.manager.run()
            // What clearing app storage does: the bytes go, the database does not know.
            world.store.delete(world.manga.id, chapter.id)

            val result = world.verify()

            assertEquals(1, result.staleRecords)
            assertNull(world.downloads.get(chapter.id), "the record outlived its files")
            assertTrue(result.reclaimedAnything)
        }

    @Test
    fun `a downloaded directory no record claims is deleted`() =
        runTest {
            val world = world()
            val chapter = world.chapters[0]
            world.queue(chapter)
            world.manager.run()
            // What a sync's prune leaves: the chapter row cascades the download record away and cannot
            // reach a single byte on disk.
            world.downloads.delete(listOf(chapter.id))

            val result = world.verify()

            assertEquals(1, result.orphanDirectories)
            assertEquals(emptyList(), world.store.storedChapters())
        }

    @Test
    fun `a part directory no record claims is discarded`() =
        runTest {
            val world = world()
            val chapter = world.chapters[0]
            world.store.seedPart(world.manga.id, chapter.id, indices = listOf(0))

            val result = world.verify()

            assertEquals(1, result.discardedParts)
            assertEquals(0, result.orphanDirectories, "a part is not a download")
            assertTrue(!world.store.hasPart(world.manga.id, chapter.id))
        }

    @Test
    fun `a part belonging to a queued chapter is left alone`() =
        runTest {
            val world = world()
            val chapter = world.chapters[0]
            world.queue(chapter)
            world.store.seedPart(world.manga.id, chapter.id, indices = listOf(0))

            val result = world.verify()

            // The safety rule. A queued chapter's part is the resume state a retry needs, and the engine
            // always writes the record before it opens the part — so "has a record" is what separates
            // work in flight from debris, with no lock between the sweep and a running queue.
            assertEquals(0, result.discardedParts)
            assertTrue(world.store.hasPart(world.manga.id, chapter.id))
            assertEquals(1, world.downloads.pending().size)
        }

    @Test
    fun `a queued chapter with nothing on disk is not mistaken for drift`() =
        runTest {
            val world = world()
            world.queue(world.chapters[0])

            val result = world.verify()

            // Having no files is what a queued chapter *is*, not an inconsistency.
            assertEquals(VerifyDownloadsResult(), result)
            assertEquals(1, world.downloads.pending().size)
        }

    @Test
    fun `a failed chapter keeps both its record and what it fetched`() =
        runTest {
            val world = world()
            val chapter = world.chapters[0]
            world.queue(chapter)
            world.store.seedPart(world.manga.id, chapter.id, indices = listOf(0))
            world.downloads.markFailed(chapter.id, DownloadError.Network)

            val result = world.verify()

            // A failure is a chapter waiting for a retry, and the retry resumes from this part.
            assertEquals(VerifyDownloadsResult(), result)
            assertTrue(world.store.hasPart(world.manga.id, chapter.id))
        }

    @Test
    fun `a consistent device is left untouched`() =
        runTest {
            val world = world()
            world.queue(world.chapters[0], world.chapters[1])
            world.manager.run()

            val result = world.verify()

            assertEquals(VerifyDownloadsResult(), result)
            assertTrue(!result.reclaimedAnything)
            assertEquals(2, world.store.storedChapters().count(StoredChapter::isComplete))
            assertEquals(2, world.downloads.getAll().count(ChapterDownload::isComplete))
        }

    @Test
    fun `an empty device sweeps nothing`() =
        runTest {
            val result = world().verify()

            assertEquals(VerifyDownloadsResult(), result)
        }

    private fun TestScope.world() =
        DownloadWorld(dispatchers = TestDispatchers(UnconfinedTestDispatcher(testScheduler)))
}
