package app.tanuki.core.data.repository.chapter

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

/**
 * Room DAO for [ChapterEntity] and its local-only [ReadingProgressEntity]. Reads LEFT JOIN progress so
 * a single [ChapterWithProgress] projection serves every query.
 */
@Dao
interface ChapterDao {
    @Query(
        """
        SELECT chapters.*,
               reading_progress.has_bookmark, reading_progress.last_page_read, reading_progress.already_read
        FROM chapters
        LEFT JOIN reading_progress ON reading_progress.chapter_id = chapters.id
        WHERE chapters.manga_id = :mangaId
        ORDER BY chapters.chapter_number
        """,
    )
    fun observeChapters(mangaId: Uuid): Flow<List<ChapterWithProgress>>

    @Query(
        """
        SELECT chapters.*,
               reading_progress.has_bookmark, reading_progress.last_page_read, reading_progress.already_read
        FROM chapters
        LEFT JOIN reading_progress ON reading_progress.chapter_id = chapters.id
        WHERE chapters.manga_id = :mangaId AND reading_progress.has_bookmark = 1
        ORDER BY chapters.chapter_number
        """,
    )
    fun observeBookmarkedChapters(mangaId: Uuid): Flow<List<ChapterWithProgress>>

    @Query(
        """
        SELECT chapters.*,
               reading_progress.has_bookmark, reading_progress.last_page_read, reading_progress.already_read
        FROM chapters
        LEFT JOIN reading_progress ON reading_progress.chapter_id = chapters.id
        WHERE chapters.manga_id = :mangaId
        ORDER BY chapters.chapter_number
        """,
    )
    suspend fun getChapters(mangaId: Uuid): List<ChapterWithProgress>

    @Query(
        """
        SELECT chapters.*,
               reading_progress.has_bookmark, reading_progress.last_page_read, reading_progress.already_read
        FROM chapters
        LEFT JOIN reading_progress ON reading_progress.chapter_id = chapters.id
        WHERE chapters.id = :chapterId
        """,
    )
    suspend fun getChapter(chapterId: Uuid): ChapterWithProgress?

    /**
     * [getChapter] for many ids at once — what a caller resolving a list of chapter ids (history rows,
     * update records, download records) uses instead of loading *every* chapter of each owning series
     * and searching it. Ids with no row are absent from the result, and the order is unspecified;
     * callers key it by id.
     */
    @Query(
        """
        SELECT chapters.*,
               reading_progress.has_bookmark, reading_progress.last_page_read, reading_progress.already_read
        FROM chapters
        LEFT JOIN reading_progress ON reading_progress.chapter_id = chapters.id
        WHERE chapters.id IN (:chapterIds)
        """,
    )
    suspend fun getChaptersByIds(chapterIds: Collection<Uuid>): List<ChapterWithProgress>

    /**
     * [getChaptersByIds] as a subscription — for the one kind of caller that must notice the user opening
     * a chapter, rather than only find out at its next read.
     *
     * The `ORDER BY` is what makes that affordable. This query is invalidated by every `reading_progress`
     * write, which the reader makes on every page turn, so a live consumer deduplicates its emissions
     * against the previous one — and an unordered `IN` lets SQLite return the same rows in a different
     * order, which would make that comparison silently useless. Ordering by id costs a sort over a
     * handful of rows and makes two equal answers compare equal. ([getChaptersByIds] needs none of this:
     * its caller keys the result by id and never compares two reads.)
     */
    @Query(
        """
        SELECT chapters.*,
               reading_progress.has_bookmark, reading_progress.last_page_read, reading_progress.already_read
        FROM chapters
        LEFT JOIN reading_progress ON reading_progress.chapter_id = chapters.id
        WHERE chapters.id IN (:chapterIds)
        ORDER BY chapters.id
        """,
    )
    fun observeChaptersByIds(chapterIds: Collection<Uuid>): Flow<List<ChapterWithProgress>>

    @Upsert
    suspend fun upsertChapter(chapter: ChapterEntity)

    /**
     * Bulk upsert for a source sync. Chapters only — progress rows are untouched, which is the point:
     * a sync must never write the user's reading state, and a matched chapter keeps its `id`, so its
     * existing progress row stays attached.
     */
    @Upsert
    suspend fun upsertChapters(chapters: List<ChapterEntity>)

    @Upsert
    suspend fun upsertProgress(progress: ReadingProgressEntity)

    /**
     * Bulk progress upsert, committed as one transaction — what a multi-select "mark as read" writes.
     * Unlike [upsertChapters] this *is* the user's reading state, so it deliberately writes the progress
     * table and leaves the source-owned chapter rows alone.
     */
    @Upsert
    suspend fun upsertProgress(progress: List<ReadingProgressEntity>)

    @Query("DELETE FROM reading_progress WHERE chapter_id = :chapterId")
    suspend fun deleteProgress(chapterId: Uuid)

    @Query("DELETE FROM chapters WHERE id = :chapterId")
    suspend fun deleteById(chapterId: Uuid)

    @Query("DELETE FROM chapters WHERE id IN (:chapterIds)")
    suspend fun deleteByIds(chapterIds: List<Uuid>)

    /**
     * Upserts a chapter and reconciles its local progress in one transaction: progress is cleared when
     * absent, otherwise upserted, so it commits together with the chapter.
     */
    @Transaction
    suspend fun upsertWithProgress(
        chapter: ChapterEntity,
        progress: ReadingProgressEntity?,
    ) {
        upsertChapter(chapter)
        when (progress) {
            null -> deleteProgress(chapter.id)
            else -> upsertProgress(progress)
        }
    }
}
