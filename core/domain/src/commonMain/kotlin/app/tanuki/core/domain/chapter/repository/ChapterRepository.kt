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
     * Inserts or update a new chapter.
     *
     * @param chapter The chapter to persist.
     */
    suspend fun upsertChapter(chapter: Chapter)

    /**
     * Deletes a chapter by its local id.
     *
     * @param chapterId Local id of the chapter to remove.
     */
    suspend fun deleteChapter(chapterId: Uuid)
}