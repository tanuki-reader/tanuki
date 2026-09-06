package app.tanuki.core.domain.download.usecase

import app.tanuki.core.domain.download.model.DownloadError
import app.tanuki.core.domain.download.model.DownloadState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests what the queue screen reads: the grouped view, and the sweep that empties it.
 *
 * The two properties worth holding onto are that a **failed** chapter stays visible — it is the only row
 * on that screen that needs a decision, and dropping it would make a stopped download vanish from
 * everywhere the user might look — and that clearing the queue is genuinely not the same operation as
 * deleting downloads, which is the one pair of actions on this feature that must never be confusable.
 */
class DownloadQueueViewTest {
    @Test
    fun `the queue groups chapters under their series in the order the engine will work them`() =
        runTest {
            val world = world()
            world.queue(world.chapters[2], world.chapters[0], world.chapters[1])

            val queue = world.queueView().first()

            assertEquals(1, queue.size)
            val series = queue.single()
            assertEquals(world.manga.id, series.manga.id)
            assertEquals(
                listOf(world.chapters[2].id, world.chapters[0].id, world.chapters[1].id),
                series.chapters.map { it.chapter.id },
                "oldest-queued first, which is the order the engine takes them in",
            )
        }

    @Test
    fun `a failed chapter stays in the queue and is counted as failed`() =
        runTest {
            val world = world()
            world.queue(world.chapters[0], world.chapters[1])
            world.fetcher.alwaysFail(world.loader.pageUrl("md-1", 0), DownloadError.Network)
            world.manager.run()

            val series = world.queueView().first().single()

            // Chapter 1 failed and chapter 2 downloaded, so exactly one row is left — the one that needs
            // the user to decide something.
            assertEquals(listOf(world.chapters[0].id), series.chapters.map { it.chapter.id })
            assertEquals(DownloadState.Failed, series.chapters.single().download.state)
            assertEquals(DownloadError.Network, series.chapters.single().download.error)
            assertEquals(1, series.failedCount)
        }

    @Test
    fun `a downloaded chapter leaves the queue`() =
        runTest {
            val world = world()
            world.queue(world.chapters[0])
            world.manager.run()

            assertTrue(world.queueView().first().isEmpty(), "finished work is library, not queue")
        }

    @Test
    fun `cancelling everything empties the queue and its part directories`() =
        runTest {
            val world = world()
            world.queue(world.chapters[0], world.chapters[1])
            world.store.seedPart(world.manga.id, world.chapters[0].id, indices = listOf(0))

            val cancelled = world.cancelAll()

            assertEquals(2, cancelled)
            assertTrue(world.queueView().first().isEmpty())
            assertTrue(
                !world.store.hasPart(world.manga.id, world.chapters[0].id),
                "a cancelled chapter's half-fetched pages are bytes nothing will ever finish",
            )
        }

    @Test
    fun `cancelling everything keeps chapters that are already downloaded`() =
        runTest {
            val world = world()
            world.queue(world.chapters[0])
            world.manager.run()
            world.queue(world.chapters[1])

            val cancelled = world.cancelAll()

            assertEquals(1, cancelled, "only the chapter still waiting")
            val kept = assertNotNull(world.record(world.chapters[0]))
            assertEquals(DownloadState.Downloaded, kept.state)
            assertNotNull(
                world.store.pagePaths(world.manga.id, world.chapters[0].id),
                "clearing a queue must never reach the files the user already has",
            )
        }

    @Test
    fun `cancelling an empty queue does nothing`() =
        runTest {
            val world = world()

            assertEquals(0, world.cancelAll())
        }

    private fun TestScope.world(pagesPerChapter: Int = 2) =
        DownloadWorld(
            dispatchers = TestDispatchers(UnconfinedTestDispatcher(testScheduler)),
            pagesPerChapter = pagesPerChapter,
        )
}
