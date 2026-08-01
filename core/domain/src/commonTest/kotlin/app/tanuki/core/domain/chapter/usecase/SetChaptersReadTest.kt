package app.tanuki.core.domain.chapter.usecase

import app.tanuki.core.domain.chapter.model.Chapter
import app.tanuki.core.domain.chapter.model.ChapterNumber
import app.tanuki.core.domain.chapter.model.ReadingProgress
import app.tanuki.core.domain.chapter.repository.ChapterRepository
import app.tanuki.core.domain.common.model.Text
import app.tanuki.core.domain.common.model.Url
import app.tanuki.core.domain.common.service.uuidV7
import app.tanuki.core.domain.update.model.ChapterUpdate
import app.tanuki.core.domain.update.repository.ChapterUpdateRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Tests [SetChaptersRead] — the multi-select "mark as read" the chapter list drives. What matters is that
 * it asserts a state rather than replaying a reading session: it goes both ways, rewinds the page when
 * un-reading, keeps bookmarks, writes the whole selection in one call, and leaves history alone.
 */
class SetChaptersReadTest {
    @Test
    fun `marks every selected chapter read in one write`() = runTest {
        val chapters = MarkChapterRepo(chapters = chapters(unread = 3))
        val setRead = SetChaptersRead.Default(chapters, MarkChapterUpdateRepo())

        setRead(chapters.mangaId, chapters.ids.toSet(), read = true)

        val written = chapters.written.single()
        assertEquals(chapters.ids.toSet(), written.keys)
        assertTrue(written.values.all { it.alreadyRead })
    }

    @Test
    fun `marks selected chapters unread and rewinds them to the first page`() = runTest {
        val chapters =
            MarkChapterRepo(
                chapters =
                    chapters(unread = 1) {
                        it.copy(progress = ReadingProgress(hasBookmark = false, lastPageRead = 37u, alreadyRead = true))
                    }
            )
        val setRead = SetChaptersRead.Default(chapters, MarkChapterUpdateRepo())

        setRead(chapters.mangaId, chapters.ids.toSet(), read = false)

        val written = chapters.written.single().values.single()
        assertFalse(written.alreadyRead)
        // An "unread" chapter that still resumed on page 38 would contradict its own badge.
        assertEquals(0u, written.lastPageRead)
    }

    @Test
    fun `keeps a bookmark the user set on a chapter it marks`() = runTest {
        val chapters =
            MarkChapterRepo(
                chapters =
                    chapters(unread = 1) {
                        it.copy(progress = ReadingProgress(hasBookmark = true, lastPageRead = 4u, alreadyRead = false))
                    }
            )
        val setRead = SetChaptersRead.Default(chapters, MarkChapterUpdateRepo())

        setRead(chapters.mangaId, chapters.ids.toSet(), read = true)

        val written = chapters.written.single().values.single()
        assertTrue(written.hasBookmark)
        // Marking read keeps where the user actually stopped, so resuming still lands there.
        assertEquals(4u, written.lastPageRead)
    }

    @Test
    fun `writes nothing when every selected chapter is already in the requested state`() = runTest {
        val chapters =
            MarkChapterRepo(
                chapters =
                    chapters(unread = 2) {
                        it.copy(progress = ReadingProgress(hasBookmark = false, lastPageRead = 0u, alreadyRead = true))
                    }
            )
        val updates = MarkChapterUpdateRepo()
        val setRead = SetChaptersRead.Default(chapters, updates)

        setRead(chapters.mangaId, chapters.ids.toSet(), read = true)

        assertTrue(chapters.written.isEmpty())
        assertTrue(updates.cleared.isEmpty())
    }

    @Test
    fun `un-reads a chapter the user only got partway through`() = runTest {
        val chapters =
            MarkChapterRepo(
                chapters =
                    chapters(unread = 1) {
                        it.copy(progress = ReadingProgress(hasBookmark = false, lastPageRead = 12u, alreadyRead = false))
                    }
            )
        val setRead = SetChaptersRead.Default(chapters, MarkChapterUpdateRepo())

        setRead(chapters.mangaId, chapters.ids.toSet(), read = false)

        // Its read flag was already false, but the saved page is exactly what "unread" has to clear.
        assertEquals(0u, chapters.written.single().values.single().lastPageRead)
    }

    @Test
    fun `ignores ids that belong to another manga`() = runTest {
        val chapters = MarkChapterRepo(chapters = chapters(unread = 2))
        val setRead = SetChaptersRead.Default(chapters, MarkChapterUpdateRepo())

        setRead(chapters.mangaId, setOf(chapters.ids.first(), uuidV7()), read = true)

        assertEquals(setOf(chapters.ids.first()), chapters.written.single().keys)
    }

    @Test
    fun `clears the new-chapter records of the chapters it marks read`() = runTest {
        val chapters = MarkChapterRepo(chapters = chapters(unread = 2))
        val updates = MarkChapterUpdateRepo()
        val setRead = SetChaptersRead.Default(chapters, updates)

        setRead(chapters.mangaId, chapters.ids.toSet(), read = true)

        assertEquals(listOf(chapters.mangaId to chapters.ids), updates.cleared)
    }

    @Test
    fun `does not re-list chapters in the updates feed when marking them unread`() = runTest {
        val chapters =
            MarkChapterRepo(
                chapters =
                    chapters(unread = 2) {
                        it.copy(progress = ReadingProgress(hasBookmark = false, lastPageRead = 9u, alreadyRead = true))
                    }
            )
        val updates = MarkChapterUpdateRepo()
        val setRead = SetChaptersRead.Default(chapters, updates)

        setRead(chapters.mangaId, chapters.ids.toSet(), read = false)

        assertTrue(updates.cleared.isEmpty())
    }

    @Test
    fun `writes nothing for an empty selection`() = runTest {
        val chapters = MarkChapterRepo(chapters = chapters(unread = 2))
        val setRead = SetChaptersRead.Default(chapters, MarkChapterUpdateRepo())

        setRead(chapters.mangaId, emptySet(), read = true)

        assertTrue(chapters.written.isEmpty())
    }
}

/** [count] chapters with no progress (so: unread), optionally rewritten by [progress]. */
private fun chapters(
    unread: Int,
    progress: (Chapter) -> Chapter = { it }
): List<Chapter> =
    List(unread) { index ->
        progress(
            Chapter(
                id = uuidV7(),
                mangaId = Uuid.NIL,
                chapterNumber = ChapterNumber.orNull(index + 1.0),
                name = null,
                scanlator = null,
                sourceChapterKey = Text("md-$index"),
                url = Url("https://mangadex.org/chapter/md-$index"),
                dateUpload = null,
                dateFetch = Instant.fromEpochMilliseconds(1_000),
                progress = null
            )
        )
    }

private class MarkChapterRepo(private val chapters: List<Chapter>) : ChapterRepository {
    val mangaId: Uuid = uuidV7()
    val ids: List<Uuid> = chapters.map(Chapter::id)
    val written = mutableListOf<Map<Uuid, ReadingProgress>>()

    override suspend fun getChapters(mangaId: Uuid): List<Chapter> =
        if (mangaId == this.mangaId) chapters.map { it.copy(mangaId = mangaId) } else emptyList()

    override suspend fun updateProgress(progress: Map<Uuid, ReadingProgress>) {
        written += progress
    }

    override fun observeChapters(mangaId: Uuid): Flow<List<Chapter>> = flowOf(emptyList())

    override fun observeBookmarkedChapters(mangaId: Uuid): Flow<List<Chapter>> = flowOf(emptyList())

    override suspend fun getChapter(chapterId: Uuid): Chapter? = error("unused")

    override suspend fun updateProgress(chapterId: Uuid, progress: ReadingProgress) = error("unused")

    override suspend fun upsertChapter(chapter: Chapter) = error("unused")

    override suspend fun upsertChapters(chapters: List<Chapter>) = error("unused")

    override suspend fun deleteChapter(chapterId: Uuid) = error("unused")

    override suspend fun deleteChapters(chapterIds: List<Uuid>) = error("unused")
}

private class MarkChapterUpdateRepo : ChapterUpdateRepository {
    val cleared = mutableListOf<Pair<Uuid, List<Uuid>>>()

    override suspend fun deleteChaptersForManga(
        mangaId: Uuid,
        chapterIds: List<Uuid>
    ) {
        cleared += mangaId to chapterIds
    }

    override fun observeUpdates(): Flow<List<ChapterUpdate>> = flowOf(emptyList())

    override suspend fun getUnnotified(): List<ChapterUpdate> = error("unused")

    override suspend fun recordUpdates(updates: List<ChapterUpdate>) = error("unused")

    override suspend fun markNotified(chapterIds: List<Uuid>) = error("unused")

    override suspend fun deleteForManga(mangaId: Uuid) = error("unused")
}
