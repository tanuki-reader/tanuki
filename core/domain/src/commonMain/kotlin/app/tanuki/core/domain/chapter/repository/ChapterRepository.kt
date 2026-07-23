package app.tanuki.core.domain.chapter.repository

import app.tanuki.core.domain.chapter.model.Chapter
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