package app.tanuki.core.domain.download.usecase

import app.tanuki.core.domain.manga.usecase.RemoveMangaFromLibrary
import app.tanuki.core.domain.update.model.ChapterUpdate
import app.tanuki.core.domain.update.repository.ChapterUpdateRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
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
 * Tests the hooks that make downloads leave when the thing they were downloaded for does.
 *
 * These are the leaks the design calls out (`docs/design-downloads.md` §9), and they share one cause: a
 * foreign key can cascade a *record* away and cannot touch a byte on disk. So every one of these
 * assertions is about the **files**, and the record going too is the easy half.
 */
class DownloadCascadeTest {
    @Test
    fun `removing a series from the library takes its downloads with it`() =
        runTest {
            val world = world()
            world.queue(world.chapters[0], world.chapters[1])
            world.manager.run()
            assertTrue(world.store.storedChapters().isNotEmpty(), "the fixture has to start with files")

            world.removeFromLibrary()(world.manga.id)

            assertEquals(emptyList(), world.store.storedChapters())
            assertTrue(world.downloads.getAll().isEmpty())
        }

    @Test
    fun `the series itself survives being removed`() =
        runTest {
            val world = world()
            world.queue(world.chapters[0])
            world.manager.run()

            world.removeFromLibrary()(world.manga.id)

            // Only what was kept *for* the library goes. The cached series stays browsable and
            // re-addable, which is the promise the removal has always made.
            assertNotNull(world.mangaRepository.observeManga(world.manga.id))
            assertNotNull(world.chapterRepository.getChapter(world.chapters[0].id))
        }

    @Test
    fun `a chapter the source stopped listing takes its files`() =
        runTest {
            val world = world()
            val chapter = world.chapters[0]
            world.queue(chapter)
            world.manager.run()

            // What the sync's prune does, in the order it does it: the downloads first, because after
            // the chapter row is gone its download record has cascaded away and nothing can any longer
            // say which files belonged to it.
            world.deleteChapters(listOf(chapter.id))

            assertNull(world.store.pagePaths(world.manga.id, chapter.id))
            assertNull(world.downloads.get(chapter.id))
        }

    private fun TestScope.world() =
        DownloadWorld(dispatchers = TestDispatchers(UnconfinedTestDispatcher(testScheduler)))

    /** The real use case over the world's repositories — a sealed one cannot be doubled from here. */
    private fun DownloadWorld.removeFromLibrary(): RemoveMangaFromLibrary =
        RemoveMangaFromLibrary.Default(mangaRepository, CascadeChapterUpdateRepository(), deleteManga)
}

/** Enough of a [ChapterUpdateRepository] for the removal path, which only ever clears a series. */
private class CascadeChapterUpdateRepository : ChapterUpdateRepository {
    override fun observeUpdates(): Flow<List<ChapterUpdate>> = flowOf(emptyList())

    override suspend fun getUnnotified(): List<ChapterUpdate> = emptyList()

    override suspend fun recordUpdates(updates: List<ChapterUpdate>) = Unit

    override suspend fun markNotified(chapterIds: List<Uuid>) = Unit

    override suspend fun deleteForManga(mangaId: Uuid) = Unit

    override suspend fun deleteChaptersForManga(
        mangaId: Uuid,
        chapterIds: List<Uuid>,
    ) = Unit
}
