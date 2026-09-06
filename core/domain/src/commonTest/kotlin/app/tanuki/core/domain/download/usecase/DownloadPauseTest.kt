package app.tanuki.core.domain.download.usecase

import app.tanuki.core.domain.download.model.DownloadState
import app.tanuki.core.domain.download.usecase.DownloadRunResult.Completed
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests pausing — the one control that stops the engine without taking its work away.
 *
 * Everything else that ends a run does so by deleting rows, so the engine simply finds nothing left.
 * Pause is the exception: the rows stay, and the property that has to hold is that a paused chapter is
 * *indistinguishable from one that was never started* except for the pages already on disk. If it were
 * left `Downloading` the next launch would report it as debris; if its `.part` were discarded, resuming
 * would re-fetch bytes the user already paid for.
 */
class DownloadPauseTest {
    @Test
    fun `a run started while paused fetches nothing`() =
        runTest {
            val world = world()
            world.queue(*world.chapters.toTypedArray())
            world.setPaused(true)

            val result = world.manager.run()

            assertEquals(Completed(downloaded = 0, failed = 0), result)
            assertTrue(world.fetcher.fetched.isEmpty(), "a paused queue must not reach the network")
            assertEquals(3, world.downloads.pending().size, "and must leave every chapter in the queue")
        }

    @Test
    fun `pausing between chapters stops the run and leaves the rest queued`() =
        runTest {
            val world = world()
            world.queue(world.chapters[0], world.chapters[1], world.chapters[2])
            // Pause as the first chapter's last page lands, so the pause is seen at the chapter boundary
            // rather than inside one.
            world.fetcher.onFetch = { url -> if (url == world.loader.pageUrl("md-1", 1)) world.setPaused(true) }

            val result = world.manager.run()

            assertEquals(Completed(downloaded = 1, failed = 0), result)
            assertEquals(DownloadState.Downloaded, assertNotNull(world.record(world.chapters[0])).state)
            assertEquals(DownloadState.Queued, assertNotNull(world.record(world.chapters[1])).state)
            assertEquals(DownloadState.Queued, assertNotNull(world.record(world.chapters[2])).state)
        }

    @Test
    fun `pausing inside a chapter leaves it queued rather than downloading or failed`() =
        runTest {
            val world = world(pagesPerChapter = 6)
            world.queue(world.chapters[0])
            world.fetcher.onFetch = { url -> if (url == world.loader.pageUrl("md-1", 0)) world.setPaused(true) }

            val result = world.manager.run()

            assertEquals(Completed(downloaded = 0, failed = 0), result, "a pause is not a failure")
            val record = assertNotNull(world.record(world.chapters[0]))
            // `Downloading` would be read as debris by the next launch's requeue sweep, and shown in the
            // queue screen as active while nothing is running.
            assertEquals(DownloadState.Queued, record.state)
            assertNull(record.error)
            assertNull(
                world.store.pagePaths(world.manga.id, world.chapters[0].id),
                "a partial chapter must never present itself as readable",
            )
        }

    @Test
    fun `a paused chapter keeps what it fetched and the row says so`() =
        runTest {
            val world = world(pagesPerChapter = 6)
            world.queue(world.chapters[0])
            world.fetcher.onFetch = { url -> if (url == world.loader.pageUrl("md-1", 0)) world.setPaused(true) }

            world.manager.run()

            val record = assertNotNull(world.record(world.chapters[0]))
            assertTrue(record.downloadedPages > 0, "the pages that landed are still counted")
            assertTrue(record.downloadedPages < 6)
            assertTrue(record.bytes > 0L, "and so are their bytes")
            assertEquals(6, record.pageCount, "the page list was resolved and must not be forgotten")
            assertTrue(world.store.hasPart(world.manga.id, world.chapters[0].id), "the part is kept to resume from")
        }

    @Test
    fun `resuming picks the chapter back up and only fetches what is missing`() =
        runTest {
            val world = world(pagesPerChapter = 6)
            world.queue(world.chapters[0])
            world.fetcher.onFetch = { url -> if (url == world.loader.pageUrl("md-1", 0)) world.setPaused(true) }
            world.manager.run()
            val beforeResume = world.fetcher.fetched.toList()

            world.fetcher.onFetch = {}
            world.setPaused(false)
            val result = world.manager.run()

            assertEquals(Completed(downloaded = 1, failed = 0), result)
            assertEquals(6, assertNotNull(world.store.pagePaths(world.manga.id, world.chapters[0].id)).size)
            val refetched = world.fetcher.fetched.drop(beforeResume.size).intersect(beforeResume.toSet())
            assertTrue(refetched.isEmpty(), "a resumed chapter must not pay for its pages twice: " + refetched)
        }

    @Test
    fun `resuming asks for the queue to run and pausing does not`() =
        runTest {
            val world = world()

            world.setPaused(true)
            assertEquals(0, world.scheduler.starts, "pausing has nothing to start")

            world.setPaused(false)
            // Without this the flag would clear and the queue would sit there until something else
            // happened to enqueue a chapter — indistinguishable, from the outside, from a dead button.
            assertEquals(1, world.scheduler.starts)
        }

    private fun TestScope.world(pagesPerChapter: Int = 2) =
        DownloadWorld(
            dispatchers = TestDispatchers(UnconfinedTestDispatcher(testScheduler)),
            pagesPerChapter = pagesPerChapter,
        )
}