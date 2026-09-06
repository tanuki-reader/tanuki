package app.tanuki.core.domain.download.usecase

import app.tanuki.core.domain.settings.model.AutoDownloadMode
import app.tanuki.core.domain.settings.model.DeleteAfterReading
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Tests the two rules that act on the user's behalf: queueing chapters nobody asked for, and deleting
 * files nobody asked to lose.
 *
 * Both are off by default, and the first thing worth proving is that they *stay* off — a settings
 * subsystem whose defaults leak is one that spends someone's mobile data or removes their downloads on
 * an upgrade. After that, the interesting cases are all about restraint: what each rule refuses to
 * touch.
 */
class DownloadAutomationTest {
    // --- Auto-download ------------------------------------------------------------------------

    @Test
    fun `nothing is downloaded automatically until the user asks for it`() =
        runTest {
            val world = world()

            val queued = world.autoDownload(world.manga, world.chapters)

            assertEquals(0, queued)
            assertTrue(world.downloads.pending().isEmpty(), "the default must never spend a user's data")
        }

    @Test
    fun `every new chapter is queued under the all-library mode`() =
        runTest {
            val world = world()
            world.settings.setAutoDownload(AutoDownloadMode.AllLibrary, emptySet())

            val queued = world.autoDownload(world.manga, world.chapters)

            assertEquals(3, queued)
            assertEquals(3, world.downloads.pending().size)
            assertEquals(1, world.scheduler.starts, "one wake-up for the batch, not one per chapter")
        }

    @Test
    fun `a series outside the library is never auto-downloaded`() =
        runTest {
            val world = world()
            world.settings.setAutoDownload(AutoDownloadMode.AllLibrary, emptySet())

            // A sync also runs while the user is merely browsing a source. Downloading a series they
            // have not saved would spend their storage on something they were only looking at.
            val queued = world.autoDownload(world.manga.copy(dateAdded = null), world.chapters)

            assertEquals(0, queued)
        }

    @Test
    fun `the per-category mode only covers series in a chosen category`() =
        runTest {
            val world = world()
            val chosen = Uuid.random()
            world.settings.setAutoDownload(AutoDownloadMode.SelectedCategories, setOf(chosen))

            world.categories.place(world.manga.id, setOf(Uuid.random()))
            assertEquals(0, world.autoDownload(world.manga, world.chapters), "a category that wasn't picked")

            world.categories.place(world.manga.id, setOf(chosen))
            assertEquals(3, world.autoDownload(world.manga, world.chapters))
        }

    @Test
    fun `the per-category mode with nothing chosen downloads nothing`() =
        runTest {
            val world = world()
            world.settings.setAutoDownload(AutoDownloadMode.SelectedCategories, emptySet())
            world.categories.place(world.manga.id, setOf(Uuid.random()))

            // "Download for these categories", with none picked, cannot mean "all of them" — guessing
            // the generous reading would spend data on a half-configured setting.
            assertEquals(0, world.autoDownload(world.manga, world.chapters))
        }

    // --- Delete after reading -----------------------------------------------------------------

    @Test
    fun `nothing is deleted after reading until the user asks for it`() =
        runTest {
            val world = world()
            world.downloadAndRead(world.chapters[0])

            val deleted = world.deleteReadDownloads(world.manga.id)

            assertEquals(0, deleted)
            assertNotNull(world.store.pagePaths(world.manga.id, world.chapters[0].id))
        }

    @Test
    fun `a read chapter is deleted immediately when that is the setting`() =
        runTest {
            val world = world()
            world.settings.setDeleteAfterReading(DeleteAfterReading.Immediately)
            world.downloadAndRead(world.chapters[0])

            val deleted = world.deleteReadDownloads(world.manga.id)

            assertEquals(1, deleted)
            assertNull(world.store.pagePaths(world.manga.id, world.chapters[0].id))
            assertNull(world.record(world.chapters[0]), "the record goes with the files")
        }

    @Test
    fun `an unread chapter is never deleted`() =
        runTest {
            val world = world()
            world.settings.setDeleteAfterReading(DeleteAfterReading.Immediately)
            world.queue(world.chapters[0])
            world.manager.run()

            val deleted = world.deleteReadDownloads(world.manga.id)

            assertEquals(0, deleted, "nothing the user has not seen may be removed")
            assertNotNull(world.store.pagePaths(world.manga.id, world.chapters[0].id))
        }

    @Test
    fun `a bookmarked chapter survives being read`() =
        runTest {
            val world = world()
            world.settings.setDeleteAfterReading(DeleteAfterReading.Immediately)
            world.downloadAndRead(world.chapters[0], bookmarked = true)

            val deleted = world.deleteReadDownloads(world.manga.id)

            // A bookmark is the user saying "come back to this" — the opposite of what this setting is
            // for, so it outranks it.
            assertEquals(0, deleted)
            assertNotNull(world.store.pagePaths(world.manga.id, world.chapters[0].id))
        }

    @Test
    fun `keeping the last one leaves the newest read chapter and takes the rest`() =
        runTest {
            val world = world()
            world.settings.setDeleteAfterReading(DeleteAfterReading.KeepLastOne)
            world.chapters.forEach { world.downloadAndRead(it) }

            val deleted = world.deleteReadDownloads(world.manga.id)

            assertEquals(2, deleted)
            // Chapters 1 and 2 go; chapter 3 — the highest number, the one a reader flicks back to —
            // stays.
            assertNull(world.store.pagePaths(world.manga.id, world.chapters[0].id))
            assertNull(world.store.pagePaths(world.manga.id, world.chapters[1].id))
            assertNotNull(world.store.pagePaths(world.manga.id, world.chapters[2].id))
        }

    @Test
    fun `a queued chapter is not cancelled by reading a different one`() =
        runTest {
            val world = world()
            world.settings.setDeleteAfterReading(DeleteAfterReading.Immediately)
            world.downloadAndRead(world.chapters[0])
            // Chapter 2 is waiting, not finished. The rule must not reach it.
            world.queue(world.chapters[1])

            world.deleteReadDownloads(world.manga.id)

            assertNotNull(world.record(world.chapters[1]), "an unfinished download is not this rule's business")
        }

    private fun TestScope.world() =
        DownloadWorld(dispatchers = TestDispatchers(UnconfinedTestDispatcher(testScheduler)))
}
