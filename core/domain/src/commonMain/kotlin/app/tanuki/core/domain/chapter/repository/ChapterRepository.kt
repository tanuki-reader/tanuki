package app.tanuki.core.domain.chapter.repository

import app.tanuki.core.domain.chapter.model.Chapter
import app.tanuki.core.domain.chapter.model.ReadingProgress
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

/**
 * Persistence operations for [Chapter]s.
 *
 * Reads are reactive ([Flow]); writes are `suspend`. Implemented by the data layer (SQLDelight) and
 * consumed by feature use cases via Koin. Naming convention: `observe*` returns a live [Flow],
 * while `add`/`update`/`delete` mutate a single chapter.
 *
 * @see Chapter
 */
interface ChapterRepository {
    /**
     * Observes all chapters of a manga, re-emitting whenever they change.
     *
     * @param mangaId Local id of the owning manga.
     * @return A [Flow] of the manga's chapters.
     */
    fun observeChapters(mangaId: Uuid): Flow<List<Chapter>>

    /**
     * Observes only the bookmarked chapters of a manga.
     *
     * @param mangaId Local id of the owning manga.
     * @return A [Flow] of the manga's bookmarked chapters.
     */
    fun observeBookmarkedChapters(mangaId: Uuid): Flow<List<Chapter>>

    /**
     * Every chapter of a manga, read once rather than observed — what a source sync matches its
     * remote chapters against before writing.
     *
     * @param mangaId Local id of the owning manga.
     */
    suspend fun getChapters(mangaId: Uuid): List<Chapter>

    /**
     * A single chapter by its local [chapterId], read once rather than observed — how the reader
     * resolves the chapter it was opened at to its source-native key (to fetch pages) and to the user's
     * saved [ReadingProgress] (to resume at the right page).
     *
     * @param chapterId Local id of the chapter.
     * @return The [Chapter], or `null` when no such row exists.
     */
    suspend fun getChapter(chapterId: Uuid): Chapter?

    /**
     * Writes the user's [ReadingProgress] for a chapter, creating the local progress row or replacing
     * it — the reader's counterpart to the `observe`/`get` reads above.
     *
     * Touches only the local-only progress table, never the source-owned chapter row, so it is safe to
     * call as the user pages and can never be clobbered by (or clobber) a source sync. Unlike
     * [upsertChapter]'s progress reconciliation, an absent argument is not expressible here: this always
     * writes a row, so callers preserve fields they don't mean to change (e.g. a page update keeps the
     * existing bookmark) by reading the current progress first.
     *
     * @param chapterId Local id of the chapter whose progress is being written.
     * @param progress The reading state to persist.
     */
    suspend fun updateProgress(
        chapterId: Uuid,
        progress: ReadingProgress
    )

    /**
     * Writes many chapters' [ReadingProgress] in a single transaction — the bulk counterpart of
     * [updateProgress], for the chapter list's multi-select "mark as read".
     *
     * The bulk form exists for the same reason [upsertChapters] does: selecting every chapter of a long
     * series is thousands of rows, and one transaction per chapter would take seconds and leave a
     * half-marked list on screen if it were interrupted. Callers still supply a complete [ReadingProgress]
     * per chapter, so the same "read the current progress first" rule as [updateProgress] applies.
     *
     * @param progress the progress to persist, keyed by the local chapter id it belongs to.
     */
    suspend fun updateProgress(progress: Map<Uuid, ReadingProgress>)

    /**
     * Inserts or update a new chapter.
     *
     * @param chapter The chapter to persist.
     */
    suspend fun upsertChapter(chapter: Chapter)

    /**
     * Inserts or updates many chapters in a single transaction.
     *
     * The bulk form exists because a source sync writes a whole feed at once — a long series is over a
     * thousand rows, and one transaction per chapter would make a refresh take seconds and leave a
     * half-written list visible if it were interrupted.
     *
     * @param chapters The chapters to persist.
     */
    suspend fun upsertChapters(chapters: List<Chapter>)

    /**
     * Deletes a chapter by its local id.
     *
     * @param chapterId Local id of the chapter to remove.
     */
    suspend fun deleteChapter(chapterId: Uuid)

    /**
     * Deletes many chapters by local id in a single transaction — the sync's counterpart to
     * [upsertChapters], for chapters a source no longer lists.
     *
     * @param chapterIds Local ids of the chapters to remove.
     */
    suspend fun deleteChapters(chapterIds: List<Uuid>)
}