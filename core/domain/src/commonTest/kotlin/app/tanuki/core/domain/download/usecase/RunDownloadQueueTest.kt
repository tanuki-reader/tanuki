package app.tanuki.core.domain.download.usecase

import app.tanuki.core.domain.download.model.DownloadError
import app.tanuki.core.domain.download.usecase.DownloadRunResult.Completed
import app.tanuki.core.domain.download.usecase.DownloadRunResult.Skipped
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests what the user is told about a run — the half of the queue that exists only when they are *not*
 * looking at the screen.
 *
 * The properties worth holding are the quiet ones. A run that found nothing must say nothing, because
 * every app launch asks the queue to run and a notification each time would train the user to dismiss
 * the ones that matter. And a run that was skipped must not touch the notification at all: the run that
 * owns the queue owns what is shown about it, and clearing here would take down a live progress
 * notification belonging to work still in flight.
 */
class RunDownloadQueueTest {
    @Test
    fun `a run that downloads something announces what it did`() =
        runTest {
            val world = world()
            world.queue(world.chapters[0], world.chapters[1])

            val result = world.runQueue()

            assertEquals(Completed(downloaded = 2, failed = 0), result)
            assertEquals(listOf(2 to 0), world.notifier.finished)
        }

    @Test
    fun `a failed chapter is counted in what the user is told`() =
        runTest {
            val world = world()
            world.queue(world.chapters[0], world.chapters[1])
            world.fetcher.alwaysFail(world.loader.pageUrl("md-1", 0), DownloadError.Network)

            world.runQueue()

            assertEquals(listOf(1 to 1), world.notifier.finished)
        }

    @Test
    fun `a run with an empty queue says nothing at all`() =
        runTest {
            val world = world()

            val result = world.runQueue()

            assertEquals(Completed(downloaded = 0, failed = 0), result)
            assertTrue(world.notifier.finished.isEmpty(), "every launch runs the queue; silence is the point")
        }

    @Test
    fun `progress names the chapter being fetched and counts the ones behind it`() =
        runTest {
            val world = world()
            world.queue(world.chapters[0], world.chapters[1], world.chapters[2])
            // The fake fetcher returns without suspending, so a run would finish before the observer
            // that feeds the notifier ever got scheduled. A real page fetch is I/O and always
            // suspends; this is that, in virtual time.
            world.fetcher.onFetch = { delay(1) }

            world.runQueue()

            val first = world.notifier.progress.firstOrNull()
            assertTrue(first != null, "the queue moved, so something should have been reported")
            assertEquals(world.manga.id, first.manga.id)
            assertEquals(
                2,
                world.notifier.progress.first().remaining,
                "three queued means two behind the one being fetched",
            )
        }

    @Test
    fun `the ongoing report is taken down when the run ends`() =
        runTest {
            val world = world()
            world.queue(world.chapters[0])

            world.runQueue()

            assertTrue(world.notifier.clears > 0, "nothing is downloading any more, so nothing may say it is")
        }

    @Test
    fun `a skipped run leaves the running one's notification alone`() =
        runTest {
            val world = world()
            world.queue(world.chapters[0])
            // Re-enter from inside the run, which is the shape of a second enqueue landing mid-queue.
            var nested: DownloadRunResult? = null
            world.fetcher.onFetch = { if (nested == null) nested = world.runQueue() }

            world.runQueue()

            assertEquals(Skipped, nested)
            assertEquals(1, world.notifier.clears, "only the run that owned the queue cleared it")
            assertEquals(listOf(1 to 0), world.notifier.finished)
        }

    private fun TestScope.world(pagesPerChapter: Int = 2) =
        DownloadWorld(
            dispatchers = TestDispatchers(UnconfinedTestDispatcher(testScheduler)),
            pagesPerChapter = pagesPerChapter,
        )
}