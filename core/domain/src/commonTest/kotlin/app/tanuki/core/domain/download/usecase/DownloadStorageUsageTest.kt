package app.tanuki.core.domain.download.usecase

import app.tanuki.core.domain.download.model.DownloadStorageUsage
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests the storage figure, whose whole job is to be *true* — it is the number a user decides to delete
 * something on the strength of.
 *
 * ### Why these hold a live subscription instead of taking `first()`
 *
 * The bug these exist for is a *stale* reading, not a wrong one. Every delete path removes the records
 * before the files, so a reader woken only by the records re-measures while the bytes are still on disk
 * and is then never woken again. Asking the flow for `first()` after the delete starts a fresh
 * collection, measures everything from scratch, and is correct every time — it cannot see the bug at
 * all. So each test below subscribes once, acts, and asserts on the *last* thing that subscriber was
 * told, which is what the screen is actually holding.
 */
class DownloadStorageUsageTest {
    @Test
    fun `a downloaded chapter is counted against its series`() =
        runTest {
            val world = world()
            world.queue(world.chapters[0])
            world.manager.run()

            val seen = collectUsage(world)

            val usage = seen.last()
            assertTrue(usage.totalBytes > 0)
            val series = usage.series.single()
            assertEquals(world.manga.id, series.manga.id)
            assertEquals(1, series.chapterCount)
            assertEquals(usage.totalBytes, series.bytes)
        }

    @Test
    fun `deleting everything empties the total and not just the breakdown`() =
        runTest {
            val world = world()
            world.queue(world.chapters[0], world.chapters[1])
            world.manager.run()
            val seen = collectUsage(world)
            assertTrue(seen.last().totalBytes > 0, "the fixture has to start with something on disk")

            world.deleteAll()
            runCurrent()

            // The regression: the records go first, so a records-only trigger re-measured while the
            // files were still there and never fired again — leaving a total over an empty store.
            val after = seen.last()
            assertEquals(0L, after.totalBytes)
            assertTrue(after.series.isEmpty())
            assertTrue(after.isEmpty, "the screen shows its empty state off this")
        }

    @Test
    fun `deleting one series drops its bytes from the total`() =
        runTest {
            val world = world()
            world.queue(world.chapters[0])
            world.manager.run()
            val seen = collectUsage(world)
            assertTrue(seen.last().totalBytes > 0)

            world.deleteManga(world.manga.id)
            runCurrent()

            assertEquals(0L, seen.last().totalBytes)
        }

    @Test
    fun `deleting one chapter drops only that chapter's bytes`() =
        runTest {
            val world = world()
            world.queue(world.chapters[0], world.chapters[1])
            world.manager.run()
            val seen = collectUsage(world)
            val both = seen.last().totalBytes

            world.deleteChapters(listOf(world.chapters[0].id))
            runCurrent()

            val after = seen.last()
            assertTrue(after.totalBytes in 1 until both, "one chapter's worth remains — not none and not both")
            assertEquals(1, after.series.single().chapterCount)
        }

    @Test
    fun `a chapter finishing adds its bytes to a queue that is already being watched`() =
        runTest {
            val world = world()
            val seen = collectUsage(world)
            assertEquals(0L, seen.last().totalBytes)

            world.queue(world.chapters[0])
            world.manager.run()
            runCurrent()

            // The other direction: committing is a file change too, and a screen left open while a
            // download finishes must not keep saying nothing is stored.
            assertTrue(seen.last().totalBytes > 0)
        }

    @Test
    fun `nothing downloaded reads as empty rather than as unknown`() =
        runTest {
            val world = world()

            val seen = collectUsage(world)

            assertEquals(DownloadStorageUsage(), seen.single())
            assertTrue(seen.single().isEmpty)
        }

    /**
     * Subscribes for the rest of the test and records everything the subscriber is told.
     *
     * The returned list is live: read `last()` after acting to see what the screen would be showing.
     * Launched on [TestScope.backgroundScope], which is what `runTest` cancels for you at the end — a
     * collector on the test's own scope never completes and the test would simply time out.
     */
    private fun TestScope.collectUsage(world: DownloadWorld): List<DownloadStorageUsage> {
        val seen = mutableListOf<DownloadStorageUsage>()
        backgroundScope.launch { world.storageUsage().collect { seen += it } }
        runCurrent()
        return seen
    }

    private fun TestScope.world() =
        DownloadWorld(dispatchers = TestDispatchers(UnconfinedTestDispatcher(testScheduler)))
}
