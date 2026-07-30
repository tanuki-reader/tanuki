package app.tanuki.core.domain.chapter.usecase

import app.tanuki.core.domain.chapter.model.Chapter
import app.tanuki.core.domain.chapter.model.ChapterNumber
import app.tanuki.core.domain.chapter.model.ReadingProgress
import app.tanuki.core.domain.chapter.repository.ChapterRepository
import app.tanuki.core.domain.common.model.Text
import app.tanuki.core.domain.common.model.Url
import app.tanuki.core.domain.common.service.uuidV7
import app.tanuki.core.domain.history.model.ChapterHistory
import app.tanuki.core.domain.history.repository.HistoryRepository
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
 * Tests [UpdateReadingProgress]. The behaviours that matter are the invariants it enforces for every
 * caller: a page update must not wipe an existing bookmark, a chapter marked read must never be un-read
 * by scrolling back, and every write records a history entry so the chapter surfaces in continue-reading.
 */
class UpdateReadingProgressTest {

    @Test
    fun `writes the new page for a chapter with no prior progress`() = runTest {
        val chapters = ProgressChapterRepo(progress = null)
        val update = UpdateReadingProgress.Default(chapters, RecordingHistoryRepo(), RecordingChapterUpdateRepo())

        update(CHAPTER_ID, lastPageRead = 4u, read = false)

        val written = chapters.written.single()
        assertEquals(4u, written.lastPageRead)
        assertFalse(written.hasBookmark)
        assertFalse(written.alreadyRead)
    }

    @Test
    fun `preserves an existing bookmark when only the page changes`() = runTest {
        val chapters = ProgressChapterRepo(
            progress = ReadingProgress(hasBookmark = true, lastPageRead = 2u, alreadyRead = false),
        )
        val update = UpdateReadingProgress.Default(chapters, RecordingHistoryRepo(), RecordingChapterUpdateRepo())

        update(CHAPTER_ID, lastPageRead = 5u, read = false)

        val written = chapters.written.single()
        assertEquals(5u, written.lastPageRead)
        assertTrue(written.hasBookmark)
    }

    @Test
    fun `marks the chapter read when the last page is reached`() = runTest {
        val chapters = ProgressChapterRepo(progress = null)
        val update = UpdateReadingProgress.Default(chapters, RecordingHistoryRepo(), RecordingChapterUpdateRepo())

        update(CHAPTER_ID, lastPageRead = 19u, read = true)

        assertTrue(chapters.written.single().alreadyRead)
    }

    @Test
    fun `does not un-read a chapter when scrolling back after finishing`() = runTest {
        val chapters = ProgressChapterRepo(
            progress = ReadingProgress(hasBookmark = false, lastPageRead = 19u, alreadyRead = true),
        )
        val update = UpdateReadingProgress.Default(chapters, RecordingHistoryRepo(), RecordingChapterUpdateRepo())

        update(CHAPTER_ID, lastPageRead = 3u, read = false)

        val written = chapters.written.single()
        assertEquals(3u, written.lastPageRead)
        // alreadyRead is latched: reaching the end once keeps it read forever.
        assertTrue(written.alreadyRead)
    }

    @Test
    fun `records a history entry for the chapter's manga`() = runTest {
        val chapters = ProgressChapterRepo(progress = null)
        val history = RecordingHistoryRepo()
        val update = UpdateReadingProgress.Default(chapters, history, RecordingChapterUpdateRepo())

        update(CHAPTER_ID, lastPageRead = 0u, read = false)

        val recorded = history.recorded.single()
        assertEquals(CHAPTER_ID, recorded.chapterId)
        assertEquals(chapters.mangaId, recorded.mangaId)
    }

    @Test
    fun `clears the chapter's new-chapter record once it has been opened`() = runTest {
        val chapters = ProgressChapterRepo(progress = null)
        val updates = RecordingChapterUpdateRepo()
        val update = UpdateReadingProgress.Default(chapters, RecordingHistoryRepo(), updates)

        update(CHAPTER_ID, lastPageRead = 0u, read = false)

        // Opening a chapter is what retires its "new" badge — the update list is about what the user
        // hasn't seen, so reading it is the acknowledgement.
        assertEquals(listOf(chapters.mangaId to listOf(CHAPTER_ID)), updates.cleared)
    }

    @Test
    fun `writes nothing when the chapter does not exist`() = runTest {
        val chapters = ProgressChapterRepo(progress = null, chapterExists = false)
        val history = RecordingHistoryRepo()
        val updates = RecordingChapterUpdateRepo()
        val update = UpdateReadingProgress.Default(chapters, history, updates)

        update(CHAPTER_ID, lastPageRead = 4u, read = false)

        assertTrue(chapters.written.isEmpty())
        assertTrue(history.recorded.isEmpty())
        assertTrue(updates.cleared.isEmpty())
    }

    private companion object {
        val CHAPTER_ID = uuidV7()
    }
}

private class ProgressChapterRepo(
    private val progress: ReadingProgress?,
    private val chapterExists: Boolean = true
) : ChapterRepository {
    val mangaId: Uuid = uuidV7()
    val written = mutableListOf<ReadingProgress>()

    override suspend fun getChapter(chapterId: Uuid): Chapter? =
        if (!chapterExists) null
        else Chapter(
            id = chapterId,
            mangaId = mangaId,
            chapterNumber = ChapterNumber.orNull(1.0),
            name = Text("Romance Dawn"),
            scanlator = null,
            sourceChapterKey = Text("md-1"),
            url = Url("https://mangadex.org/chapter/md-1"),
            dateUpload = null,
            dateFetch = Instant.fromEpochMilliseconds(1_000),
            progress = progress,
        )

    override suspend fun updateProgress(chapterId: Uuid, progress: ReadingProgress) {
        written += progress
    }

    override fun observeChapters(mangaId: Uuid): Flow<List<Chapter>> = flowOf(emptyList())
    override fun observeBookmarkedChapters(mangaId: Uuid): Flow<List<Chapter>> = flowOf(emptyList())
    override suspend fun getChapters(mangaId: Uuid): List<Chapter> = error("unused")
    override suspend fun upsertChapter(chapter: Chapter) = error("unused")
    override suspend fun upsertChapters(chapters: List<Chapter>) = error("unused")
    override suspend fun deleteChapter(chapterId: Uuid) = error("unused")
    override suspend fun deleteChapters(chapterIds: List<Uuid>) = error("unused")
}

private class RecordingChapterUpdateRepo : ChapterUpdateRepository {
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

private class RecordingHistoryRepo : HistoryRepository {
    val recorded = mutableListOf<ChapterHistory>()

    override fun observeHistory(since: kotlin.time.Instant) = error("unused")
    override suspend fun upsertHistory(history: ChapterHistory) {
        recorded += history
    }

    override suspend fun deleteHistory(history: ChapterHistory) = error("unused")
    override suspend fun deleteHistoryForManga(mangaId: Uuid) = error("unused")
    override suspend fun clearHistory() = error("unused")
}