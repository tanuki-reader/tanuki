package app.tanuki.core.domain.download.usecase

import app.tanuki.core.domain.download.model.DownloadError
import app.tanuki.core.domain.download.model.DownloadState
import app.tanuki.core.domain.download.usecase.DownloadRunResult.Completed
import app.tanuki.core.domain.download.usecase.DownloadRunResult.Skipped
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests the engine over in-memory doubles for the three things it orchestrates — the queue, the disk and
 * the network — and the real page-resolution use case behind them (see [DownloadWorld]).
 *
 * What is worth asserting here is everything about *interruption*, because that is the only condition a
 * download subsystem is really judged on: one bad page must cost one chapter and not the queue, a
 * chapter that stops halfway must be resumable rather than restarted, and nothing half-fetched may ever
 * present itself as readable. The happy path is one test; the rest are the ways it goes wrong.
 */
class DownloadManagerTest {
    @Test
    fun `a queued chapter is fetched and committed and marked downloaded`() =
        runTest {
            val world = world()
            world.queue(world.chapters[0])

            val result = world.manager.run()

            assertEquals(Completed(downloaded = 1, failed = 0), result)
            val record = assertNotNull(world.record(world.chapters[0]))
            assertEquals(DownloadState.Downloaded, record.state)
            assertEquals(world.pagesPerChapter, record.pageCount)
            assertEquals(world.pagesPerChapter, record.downloadedPages)
            assertNotNull(record.completedAt)
            assertNull(record.error)
            // And the chapter is genuinely readable offline, not merely marked so.
            assertEquals(
                world.pagesPerChapter,
                assertNotNull(world.store.pagePaths(world.manga.id, world.chapters[0].id)).size,
            )
        }

    @Test
    fun `the queue is worked oldest first and drains completely`() =
        runTest {
            val world = world()
            world.queue(world.chapters[2], world.chapters[0], world.chapters[1])

            val result = world.manager.run()

            assertEquals(Completed(downloaded = 3, failed = 0), result)
            // Queue order, not chapter order: the user's queue is the order they asked in.
            assertEquals(listOf("md-3", "md-1", "md-2"), world.loader.asked)
        }

    @Test
    fun `the page list is fetched once per chapter at download time`() =
        runTest {
            val world = world()
            world.queue(world.chapters[0])

            world.manager.run()

            // Not resolved up front for the whole queue, and not re-resolved per page: several sources
            // hand out image URLs that expire minutes after they are issued.
            assertEquals(listOf("md-1"), world.loader.asked)
        }

    @Test
    fun `a second run while one is in flight is skipped`() =
        runTest {
            val world = world()
            world.queue(world.chapters[0])
            var nested: DownloadRunResult? = null
            world.fetcher.onFetch = { if (nested == null) nested = world.manager.run() }

            val result = world.manager.run()

            assertEquals(Skipped, nested, "a concurrent run must not work the same rows")
            assertEquals(Completed(downloaded = 1, failed = 0), result)
        }

    @Test
    fun `a page that fails twice is retried and the chapter still completes`() =
        runTest {
            val world = world()
            world.fetcher.failTimes(world.loader.pageUrl("md-1", index = 0), times = 2)
            world.queue(world.chapters[0])

            val result = world.manager.run()

            assertEquals(Completed(downloaded = 1, failed = 0), result)
            assertEquals(3, world.fetcher.fetched.count { it.endsWith("md-1/0.jpg") })
        }

    @Test
    fun `a page that never arrives fails only its chapter`() =
        runTest {
            val world = world()
            world.fetcher.alwaysFail(world.loader.pageUrl("md-2", index = 0), DownloadError.Network)
            world.queue(world.chapters[0], world.chapters[1], world.chapters[2])

            val result = world.manager.run()

            assertEquals(Completed(downloaded = 2, failed = 1), result)
            val failed = assertNotNull(world.record(world.chapters[1]))
            assertEquals(DownloadState.Failed, failed.state)
            assertEquals(DownloadError.Network, failed.error)
            assertEquals(DownloadState.Downloaded, assertNotNull(world.record(world.chapters[2])).state)
        }

    @Test
    fun `a failed chapter keeps what it fetched so a retry resumes`() =
        runTest {
            val world = world(pagesPerChapter = 3)
            world.fetcher.alwaysFail(world.loader.pageUrl("md-1", index = 2), DownloadError.Network)
            world.queue(world.chapters[0])

            world.manager.run()

            assertTrue(
                world.store.hasPart(world.manga.id, world.chapters[0].id),
                "the part directory is what makes a retry cost one page instead of the whole chapter",
            )
            // And nothing half-fetched reads as downloaded.
            assertNull(world.store.pagePaths(world.manga.id, world.chapters[0].id))
        }

    @Test
    fun `a Cloudflare challenge fails the chapter without retrying it`() =
        runTest {
            val world = world()
            world.fetcher.alwaysFail(world.loader.pageUrl("md-1", index = 0), DownloadError.NeedsVerification)
            world.queue(world.chapters[0])

            world.manager.run()

            assertEquals(DownloadError.NeedsVerification, assertNotNull(world.record(world.chapters[0])).error)
            // Asking again does not clear a challenge; retrying only spends the user's time.
            assertEquals(1, world.fetcher.fetched.count { it.endsWith("md-1/0.jpg") })
        }

    @Test
    fun `a chapter whose page list cannot be fetched fails as unavailable`() =
        runTest {
            val world = world()
            world.loader.failing += "md-1"
            world.queue(world.chapters[0])

            val result = world.manager.run()

            assertEquals(Completed(downloaded = 0, failed = 1), result)
            assertEquals(DownloadError.SourceUnavailable, assertNotNull(world.record(world.chapters[0])).error)
        }

    @Test
    fun `a chapter the source serves no pages for fails rather than committing an empty download`() =
        runTest {
            val world = world()
            world.loader.empty += "md-1"
            world.queue(world.chapters[0])

            world.manager.run()

            val record = assertNotNull(world.record(world.chapters[0]))
            assertEquals(DownloadState.Failed, record.state)
            assertEquals(DownloadError.SourceUnavailable, record.error)
            assertNull(world.store.pagePaths(world.manga.id, world.chapters[0].id))
        }

    @Test
    fun `an interrupted chapter resumes from what is already on disk`() =
        runTest {
            val world = world(pagesPerChapter = 3)
            world.store.seedPart(world.manga.id, world.chapters[0].id, indices = listOf(0, 1))
            world.queue(world.chapters[0])

            world.manager.run()

            assertEquals(
                listOf(world.loader.pageUrl("md-1", index = 2)),
                world.fetcher.fetched,
                "the two pages already on disk must not be fetched again",
            )
            assertEquals(DownloadState.Downloaded, assertNotNull(world.record(world.chapters[0])).state)
        }

    @Test
    fun `a stale part is discarded when the source's page list shrank`() =
        runTest {
            val world = world(pagesPerChapter = 2)
            // Left by an attempt when the chapter still had four pages; positions 2 and 3 no longer exist.
            world.store.seedPart(world.manga.id, world.chapters[0].id, indices = listOf(0, 1, 2, 3))
            world.queue(world.chapters[0])

            world.manager.run()

            // Everything is re-fetched rather than committing two chapters' pages interleaved.
            assertEquals(2, world.fetcher.fetched.size)
            assertEquals(DownloadState.Downloaded, assertNotNull(world.record(world.chapters[0])).state)
        }

    @Test
    fun `progress is recorded as pages land`() =
        runTest {
            val world = world(pagesPerChapter = 3)
            world.queue(world.chapters[0])

            world.manager.run()

            // Written per page rather than once at the end, so the UI, a process death and a resume all
            // agree on where a chapter got to. The leading zero is the chapter opening with nothing held.
            assertEquals(listOf(0 to 0L, 1 to 8L, 2 to 16L, 3 to 24L), world.downloads.progressWrites)
        }

    @Test
    fun `rows an earlier run left downloading are picked back up`() =
        runTest {
            val world = world()
            world.queue(world.chapters[0])
            world.downloads.update(
                assertNotNull(world.record(world.chapters[0])).copy(state = DownloadState.Downloading),
            )

            // `Downloading` only ever describes a *running* engine, so this row is the debris of a process
            // that died — not something to leave sitting in the queue screen looking active.
            val result = world.manager.run()

            assertEquals(Completed(downloaded = 1, failed = 0), result)
        }

    @Test
    fun `an aborted chapter is dropped rather than failed`() =
        runTest {
            val world = world(pagesPerChapter = 3)
            world.queue(world.chapters[0], world.chapters[1])
            world.fetcher.onFetch = { url ->
                if (url.contains("md-1")) {
                    world.manager.abort(listOf(world.chapters[0].id))
                    world.downloads.delete(listOf(world.chapters[0].id))
                }
            }

            val result = world.manager.run()

            // Neither downloaded nor failed: the user asked for it not to happen.
            assertEquals(Completed(downloaded = 1, failed = 0), result)
            assertNull(world.record(world.chapters[0]))
            assertNull(world.store.pagePaths(world.manga.id, world.chapters[0].id))
            assertEquals(DownloadState.Downloaded, assertNotNull(world.record(world.chapters[1])).state)
        }

    @Test
    fun `an empty queue is a run that does nothing`() =
        runTest {
            val world = world()

            assertEquals(Completed(downloaded = 0, failed = 0), world.manager.run())
            assertTrue(world.loader.asked.isEmpty())
        }

    private fun TestScope.world(pagesPerChapter: Int = 2) =
        DownloadWorld(
            dispatchers = TestDispatchers(UnconfinedTestDispatcher(testScheduler)),
            pagesPerChapter = pagesPerChapter,
        )
}