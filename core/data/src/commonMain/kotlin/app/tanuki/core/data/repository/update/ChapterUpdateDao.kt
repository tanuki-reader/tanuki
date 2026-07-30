package app.tanuki.core.data.repository.update

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

/** Room DAO for [ChapterUpdateEntity]. */
@Dao
interface ChapterUpdateDao {
    @Query("SELECT * FROM chapter_updates ORDER BY discovered_at DESC")
    fun observeUpdates(): Flow<List<ChapterUpdateEntity>>

    /** Oldest first — a notification reads best when it lists updates in the order they arrived. */
    @Query("SELECT * FROM chapter_updates WHERE notified = 0 ORDER BY discovered_at")
    suspend fun getUnnotified(): List<ChapterUpdateEntity>

    /**
     * Records updates, ignoring chapters already recorded.
     *
     * [OnConflictStrategy.IGNORE] rather than an upsert, and this is the whole point of the write: a
     * re-sync re-offers every chapter it considers new, and replacing the row would reset `notified`
     * and re-announce chapters the user has already been told about.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoring(updates: List<ChapterUpdateEntity>)

    @Query("UPDATE chapter_updates SET notified = 1 WHERE chapter_id IN (:chapterIds)")
    suspend fun markNotified(chapterIds: List<Uuid>)

    @Query("DELETE FROM chapter_updates WHERE manga_id = :mangaId")
    suspend fun deleteForManga(mangaId: Uuid)

    @Query("DELETE FROM chapter_updates WHERE manga_id = :mangaId AND chapter_id IN (:chapterIds)")
    suspend fun deleteForMangaAndChapters(
        mangaId: Uuid,
        chapterIds: List<Uuid>
    )
}