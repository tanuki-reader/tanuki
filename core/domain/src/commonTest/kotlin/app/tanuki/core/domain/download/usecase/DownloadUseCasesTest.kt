package app.tanuki.core.domain.download.usecase

import app.tanuki.core.domain.common.service.uuidV7
import app.tanuki.core.domain.download.model.DownloadError
import app.tanuki.core.domain.download.model.DownloadState
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests the actions the UI will bind to: putting chapters in the queue, taking them back out, and
 * removing what is already on the device.
 *
 * The theme is that every one of them is safe to press twice, and that cancelling and deleting are
 * genuinely different operations — the first must never touch a chapter that finished while the user was
 * reaching for it, and the second must reach the *files*, which no foreign key ever will.
 */
class DownloadUseCasesTest {
    @Test
    fun `enqueueing queues chapters and asks for the queue to run`() =
        runTest {
            val world = world()

            val queued = world.enqueue(world.chapters.map { it.id })

            assertEquals(3, queued)
            assertEquals(3, world.downloads.pending().size)
            assertEquals(1, world.scheduler.starts, "one wake-up per batch, not one per chapter")
        }

    @Test
    fun `enqueueing the same chapter twice does not queue it twice or move its place`() =
        runTest {
            val world = world()
            world.enqueue(listOf(world.chapters[0].id))
            val first = assertNotNull(world.record(world.chapters[0]))

            val queued = world.enqueue(listOf(world.chapters[0].id, world.chapters[0].id))

            assertEquals(0, queued)
            assertEquals(first.queuedAt, assertNotNull(world.record(world.chapters[0])).queuedAt)
        }

    @Test
    fun `enqueueing a downloaded chapter leaves its files alone`() =
        runTest {
            val world = world()
            world.queue(world.chapters[0])
            world.manager.run()

            val queued = world.enqueue(listOf(world.chapters[0].id))

            assertEquals(0, queued)
            assertEquals(DownloadState.Downloaded, assertNotNull(world.record(world.chapters[0])).state)
            assertNotNull(world.store.pagePaths(world.manga.id, world.chapters[0].id))
        }

    @Test
    fun `enqueueing a failed chapter retries it and keeps its place in the queue`() =
        runTest {
            val world = world()
            world.queue(world.chapters[0])
            world.downloads.markFailed(world.chapters[0].id, DownloadError.Network)
            val failed = assertNotNull(world.record(world.chapters[0]))

            val queued = world.enqueue(listOf(world.chapters[0].id))

            assertEquals(1, queued)
            val retried = assertNotNull(world.record(world.chapters[0]))
            assertEquals(DownloadState.Queued, retried.state)
            assertNull(retried.error)
            // A chapter that failed at position three goes back to position three, not to the end.
            assertEquals(failed.queuedAt, retried.queuedAt)
        }

    @Test
    fun `enqueueing an unknown chapter is ignored rather than failing the batch`() =
        runTest {
            val world = world()

            val queued = world.enqueue(listOf(uuidV7(), world.chapters[0].id))

            assertEquals(1, queued, "a stale id from a list rendered before a sync must not sink the rest")
        }

    @Test
    fun `next chapters queues the unread ones in reading order`() =
        runTest {
            val world = world()
            world.markRead(world.chapters[0])

            world.enqueueNext(world.manga.id, count = 1)

            assertEquals(listOf(world.chapters[1].id), world.downloads.pending().map { it.chapterId })
        }

    @Test
    fun `next chapters never queues a chapter the scanlator filter hides`() =
        runTest {
            val world = world()
            world.creditTo(world.chapters[1], "Nobody Scans")
            world.hideScanlator("Nobody Scans")

            world.enqueueNext(world.manga.id)

            // The user cannot see it in the chapter list, so downloading it spends bytes on nothing.
            assertEquals(
                listOf(world.chapters[0].id, world.chapters[2].id),
                world.downloads.pending().map { it.chapterId },
            )
        }

    @Test
    fun `next chapters with no count queues every unread chapter`() =
        runTest {
            val world = world()
            world.markRead(world.chapters[2])

            assertEquals(2, world.enqueueNext(world.manga.id))
        }

    @Test
    fun `cancelling removes a queued chapter and its partial files`() =
        runTest {
            val world = world()
            world.queue(world.chapters[0])
            world.store.seedPart(world.manga.id, world.chapters[0].id, indices = listOf(0))

            val cancelled = world.cancel(listOf(world.chapters[0].id))

            assertEquals(1, cancelled)
            assertNull(world.record(world.chapters[0]))
            assertTrue(world.store.storedChapters().isEmpty())
        }

    @Test
    fun `cancelling never touches a chapter that has already finished`() =
        runTest {
            val world = world()
            world.queue(world.chapters[0])
            world.manager.run()

            val cancelled = world.cancel(listOf(world.chapters[0].id))

            assertEquals(0, cancelled, "the thing being cancelled has already happened")
            assertNotNull(world.store.pagePaths(world.manga.id, world.chapters[0].id))
            assertEquals(DownloadState.Downloaded, assertNotNull(world.record(world.chapters[0])).state)
        }

    @Test
    fun `deleting removes a finished download from disk as well as from the index`() =
        runTest {
            val world = world()
            world.queue(world.chapters[0])
            world.manager.run()

            val deleted = world.deleteChapters(listOf(world.chapters[0].id))

            assertEquals(1, deleted)
            assertNull(world.record(world.chapters[0]))
            assertNull(world.store.pagePaths(world.manga.id, world.chapters[0].id))
            assertEquals(0L, world.store.usedBytes())
        }

    @Test
    fun `deleting a series clears its chapters and leaves other series alone`() =
        runTest {
            val world = world()
            world.queue(world.chapters[0], world.chapters[1])
            world.manager.run()
            val otherManga = uuidV7()
            world.store.seedPart(otherManga, uuidV7(), indices = listOf(0))

            val deleted = world.deleteManga(world.manga.id)

            assertEquals(2, deleted)
            assertEquals(listOf(otherManga), world.store.storedChapters().map { it.mangaId })
        }

    @Test
    fun `deleting a series reclaims its directory even when no record survives`() =
        runTest {
            val world = world()
            world.queue(world.chapters[0])
            world.manager.run()
            // What a sync that pruned the chapters leaves behind: the foreign key took the rows, the
            // bytes stayed. Reclaiming them is the whole reason this is not a loop over the records.
            world.downloads.deleteAll()

            assertEquals(0, world.deleteManga(world.manga.id))
            assertEquals(0L, world.store.usedBytes())
        }

    @Test
    fun `deleting everything empties both the queue and the disk`() =
        runTest {
            val world = world()
            world.queue(world.chapters[0], world.chapters[1])
            world.manager.run()
            world.queue(world.chapters[2])

            val deleted = world.deleteAll()

            assertEquals(3, deleted)
            assertTrue(world.downloads.getAll().isEmpty())
            assertTrue(world.store.storedChapters().isEmpty())
        }

    private fun TestScope.world() =
        DownloadWorld(dispatchers = TestDispatchers(UnconfinedTestDispatcher(testScheduler)))
}
