package app.tanuki.core.domain.download.usecase

import app.tanuki.core.domain.download.model.SourceDownloadUsage
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests the two use cases behind the offer an uninstall makes: what a source has downloaded, and
 * removing it.
 *
 * The property both hang on is that a download belongs to a source only *through its series* — nothing
 * on a download record names one. So the case that matters in every test here is the second series
 * belonging to a different source: a rule that ignored the join would pass every single-source test and
 * then delete a stranger's chapters.
 */
class SourceDownloadsTest {
    @Test
    fun `usage counts the source's downloaded chapters and what they take`() =
        runTest {
            val world = world()
            world.queue(world.chapters[0], world.chapters[1])
            world.manager.run()

            val usage = world.sourceUsage(NATIVE_ID)

            assertEquals(2, usage.chapters)
            assertTrue(usage.bytes > 0)
            assertTrue(!usage.isEmpty)
        }

    @Test
    fun `usage ignores chapters that are only queued`() =
        runTest {
            val world = world()
            world.queue(world.chapters[0])

            // Offering to delete work that has not happened yet would read as cancelling the queue,
            // which is a different action with a different button.
            assertEquals(SourceDownloadUsage(), world.sourceUsage(NATIVE_ID))
        }

    @Test
    fun `usage is empty for a source that isn't known locally`() =
        runTest {
            val world = world()
            world.queue(world.chapters[0])
            world.manager.run()

            assertEquals(SourceDownloadUsage(), world.sourceUsage("not-installed-here"))
        }

    @Test
    fun `deleting a source's downloads takes its files and records`() =
        runTest {
            val world = world()
            world.queue(world.chapters[0], world.chapters[1])
            world.manager.run()

            val deleted = world.deleteSourceDownloads(NATIVE_ID)

            assertEquals(2, deleted)
            assertTrue(world.downloads.getAll().isEmpty())
            assertEquals(emptyList(), world.store.storedChapters())
        }

    @Test
    fun `another source's downloads are left alone`() =
        runTest {
            val world = world()
            world.queue(world.chapters[0])
            world.manager.run()
            // A series from a source this uninstall is not about, downloaded the same way.
            val other = world.otherSourceChapter()

            world.deleteSourceDownloads(NATIVE_ID)

            assertEquals(listOf(other.mangaId), world.store.storedChapters().map { it.mangaId })
            assertEquals(listOf(other.chapterId), world.downloads.getAll().map { it.chapterId })
        }

    @Test
    fun `deleting for an unknown source removes nothing`() =
        runTest {
            val world = world()
            world.queue(world.chapters[0])
            world.manager.run()

            assertEquals(0, world.deleteSourceDownloads("not-installed-here"))
            assertEquals(1, world.downloads.getAll().size)
        }

    private fun TestScope.world() =
        DownloadWorld(dispatchers = TestDispatchers(UnconfinedTestDispatcher(testScheduler)))

    private companion object {
        /** The native id of the fixture's one installed source. */
        const val NATIVE_ID = "mangadex"
    }
}
