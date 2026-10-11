package app.tanuki.core.data.repository.bookmark

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

@Dao
interface PageBookmarkDao {
    /** One series' bookmarks in reading order: by chapter number (unnumbered last), then page. */
    @Query(
        """
        SELECT page_bookmark.* FROM page_bookmark
        JOIN chapters ON chapters.id = page_bookmark.chapter_id
        WHERE page_bookmark.manga_id = :mangaId
        ORDER BY chapters.chapter_number IS NULL, chapters.chapter_number, page_bookmark.page
        """,
    )
    fun observeForManga(mangaId: Uuid): Flow<List<PageBookmarkEntity>>

    @Query(
        """
        SELECT page_bookmark.*,
               manga.title AS manga_title,
               manga.cover_url AS cover_url,
               manga.cover_headers AS cover_headers,
               manga.cover_override AS cover_override,
               chapters.chapter_number AS chapter_number,
               chapters.name AS chapter_name
        FROM page_bookmark
        JOIN manga ON manga.id = page_bookmark.manga_id
        JOIN chapters ON chapters.id = page_bookmark.chapter_id
        ORDER BY page_bookmark.created_at DESC
        """,
    )
    fun observeAll(): Flow<List<PageBookmarkRow>>

    @Query("SELECT DISTINCT chapter_id FROM page_bookmark WHERE manga_id = :mangaId")
    suspend fun bookmarkedChapterIds(mangaId: Uuid): List<Uuid>

    @Upsert
    suspend fun upsert(bookmark: PageBookmarkEntity)

    /** Returns how many rows went: 1 when the page was bookmarked, else 0. */
    @Query("DELETE FROM page_bookmark WHERE chapter_id = :chapterId AND page = :page")
    suspend fun deleteAt(
        chapterId: Uuid,
        page: Int,
    ): Int

    @Query("DELETE FROM page_bookmark WHERE id = :id")
    suspend fun delete(id: Uuid)
}
