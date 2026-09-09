package app.tanuki.core.domain.chapter.usecase

import app.tanuki.core.domain.chapter.model.ReadingProgress
import app.tanuki.core.domain.chapter.repository.ChapterRepository
import app.tanuki.core.domain.download.usecase.DeleteReadChapterDownloads
import app.tanuki.core.domain.common.service.uuidV7
import app.tanuki.core.domain.history.model.ChapterHistory
import app.tanuki.core.domain.history.repository.HistoryRepository
import app.tanuki.core.domain.update.repository.ChapterUpdateRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Use case: persist the user's reading position in a chapter as they read.
 *
 * Called by the reader whenever the visible page changes (and once when a chapter opens). It writes the
 * local-only [ReadingProgress] row, so it is safe to call repeatedly and never races a source sync
 * (which touches the chapter row instead).
 *
 * Three invariants live here rather than in the ViewModel, so every caller gets them for free:
 *  - **Bookmarks survive.** The existing [ReadingProgress.hasBookmark] is read and re-written; a page
 *    update must not silently clear a bookmark the user set.
 *  - **A chapter never un-reads.** [ReadingProgress.alreadyRead] is latched: once true it stays true,
 *    even if the user later scrolls back to an earlier page. [read] only ever raises it.
 *  - **History is recorded.** The same write stamps a [ChapterHistory] entry so the chapter surfaces in
 *    "continue reading" (Home) and the History list (Activity). History is one row per chapter, so
 *    re-reading just refreshes its `readAt` — the write is upsert-idempotent like the progress write.
 */
interface UpdateReadingProgress {
    /**
     * @param chapterId Local id of the chapter being read.
     * @param lastPageRead Zero-based index of the page now visible.
     * @param read `true` when the user has reached the end of the chapter, marking it read. Never
     *   lowers an already-read chapter back to unread.
     */
    suspend operator fun invoke(
        chapterId: Uuid,
        lastPageRead: UInt,
        read: Boolean
    )

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val chapterRepository: ChapterRepository,
        private val historyRepository: HistoryRepository,
        private val chapterUpdateRepository: ChapterUpdateRepository,
        private val deleteReadChapterDownloads: DeleteReadChapterDownloads
    ) : UpdateReadingProgress {
        override suspend fun invoke(
            chapterId: Uuid,
            lastPageRead: UInt,
            read: Boolean
        ) {
            // No chapter row => nothing to write against (progress FKs the chapter); the reader only ever
            // calls this for a chapter it opened, so this is defensive rather than a real path.
            val chapter = chapterRepository.getChapter(chapterId) ?: return
            val existing = chapter.progress
            chapterRepository.updateProgress(
                chapterId,
                ReadingProgress(
                    hasBookmark = existing?.hasBookmark ?: false,
                    lastPageRead = lastPageRead,
                    alreadyRead = read || (existing?.alreadyRead ?: false)
                )
            )
            historyRepository.upsertHistory(
                ChapterHistory(
                    id = uuidV7(),
                    chapterId = chapterId,
                    mangaId = chapter.mangaId,
                    readAt = Clock.System.now()
                )
            )
            chapterUpdateRepository.deleteChaptersForManga(
                mangaId = chapter.mangaId,
                chapterIds = listOf(chapterId)
            )
            // Only on the *transition* to read. This runs on every page turn near the end of a chapter,
            // and sweeping the series' downloads each time would put a database walk between pages.
            if (read && existing?.alreadyRead != true) deleteReadChapterDownloads(chapter.mangaId)
        }
    }
}