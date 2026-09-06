package app.tanuki.core.domain.download.usecase

import app.tanuki.core.domain.download.model.DownloadHold
import app.tanuki.core.domain.download.model.DownloadState
import app.tanuki.core.domain.download.usecase.DownloadRunResult.Completed
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests "only on Wi-Fi" — a setting that was stored and shown for a phase before anything on iOS read
 * it, and is now enforced in the engine so both platforms honour it the same way.
 *
 * The theme is that a metered connection is a **hold, never a failure**. Nothing is marked failed,
 * nothing is deleted, and the queue is expected to pick itself back up when the device reaches Wi-Fi —
 * so the tests check what the rows look like afterwards as much as they check that fetching stopped.
 */
class DownloadWifiOnlyTest {
    @Test
    fun `a metered connection does not hold the queue unless the user asked`() =
        runTest {
            val world = world()
            world.connection.setMetered(true)
            world.queue(world.chapters[0])

            val result = world.manager.run()

            // The setting is off by default, so cellular is simply the network the app is on.
            assertEquals(Completed(downloaded = 1, failed = 0), result)
        }

    @Test
    fun `the queue holds on a metered connection when only-on-wifi is on`() =
        runTest {
            val world = world()
            world.settings.setDownloadOnWifiOnly(true)
            world.connection.setMetered(true)
            world.queue(world.chapters[0], world.chapters[1])

            val result = world.manager.run()

            assertEquals(Completed(downloaded = 0, failed = 0), result, "a hold is not a failure")
            assertTrue(world.fetcher.fetched.isEmpty(), "no bytes may be spent on the user's data")
            assertEquals(2, world.downloads.pending().size, "and every chapter keeps its place")
        }

    @Test
    fun `a held chapter is left queued rather than failed`() =
        runTest {
            val world = world()
            world.settings.setDownloadOnWifiOnly(true)
            world.connection.setMetered(true)
            world.queue(world.chapters[0])

            world.manager.run()

            val record = assertNotNull(world.record(world.chapters[0]))
            assertEquals(DownloadState.Queued, record.state)
            assertNull(record.error, "there is nothing for the user to retry — it resumes by itself")
        }

    @Test
    fun `moving onto Wi-Fi lets the queue finish`() =
        runTest {
            val world = world()
            world.settings.setDownloadOnWifiOnly(true)
            world.connection.setMetered(true)
            world.queue(world.chapters[0])
            assertEquals(Completed(downloaded = 0, failed = 0), world.manager.run())

            world.connection.setMetered(false)
            val result = world.manager.run()

            assertEquals(Completed(downloaded = 1, failed = 0), result)
            assertNotNull(world.store.pagePaths(world.manga.id, world.chapters[0].id))
        }

    @Test
    fun `dropping onto cellular mid-chapter stops it and keeps what it fetched`() =
        runTest {
            val world = world(pagesPerChapter = 6)
            world.settings.setDownloadOnWifiOnly(true)
            world.queue(world.chapters[0])
            // The network changes under the run, which is the case the per-page check exists for.
            world.fetcher.onFetch = { url ->
                if (url == world.loader.pageUrl("md-1", 0)) world.connection.setMetered(true)
            }

            val result = world.manager.run()

            assertEquals(Completed(downloaded = 0, failed = 0), result)
            val record = assertNotNull(world.record(world.chapters[0]))
            assertEquals(DownloadState.Queued, record.state)
            assertTrue(record.downloadedPages > 0, "the pages already paid for are kept")
            assertTrue(world.store.hasPart(world.manga.id, world.chapters[0].id), "and resumable")
        }

    @Test
    fun `pausing outranks waiting for Wi-Fi`() =
        runTest {
            val world = world()
            world.settings.setDownloadOnWifiOnly(true)
            world.connection.setMetered(true)
            world.settings.setDownloadsPaused(true)

            // The user's own instruction is the one they will remember giving, so it is the one the
            // screen has to report back.
            assertEquals(DownloadHold.Paused, world.hold().first())
        }

    @Test
    fun `the hold says what is happening in every combination`() =
        runTest {
            val world = world()
            assertEquals(DownloadHold.None, world.hold().first())

            world.connection.setMetered(true)
            assertEquals(DownloadHold.None, world.hold().first(), "metered alone holds nothing")

            world.settings.setDownloadOnWifiOnly(true)
            assertEquals(DownloadHold.WaitingForWifi, world.hold().first())

            world.connection.setMetered(false)
            assertEquals(DownloadHold.None, world.hold().first(), "Wi-Fi releases it")
        }

    private fun TestScope.world(pagesPerChapter: Int = 2) =
        DownloadWorld(
            dispatchers = TestDispatchers(UnconfinedTestDispatcher(testScheduler)),
            pagesPerChapter = pagesPerChapter,
        )
}
