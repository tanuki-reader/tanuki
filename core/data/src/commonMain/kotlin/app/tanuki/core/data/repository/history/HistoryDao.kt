package app.tanuki.core.data.repository.history

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Room DAO for [HistoryEntity]. */
@Dao
interface HistoryDao {
    /** Reactive history read at or after [since], most-recent first. */
    @Query("SELECT * FROM history WHERE read_at >= :since ORDER BY read_at DESC")
    fun observeHistorySince(since: Instant): Flow<List<HistoryEntity>>

    /**
     * One row per chapter: opening an already-read chapter just refreshes its timestamp. Written as a
     * raw upsert (rather than `@Upsert`) so the conflict is resolved on the unique `chapter_id` and the
     * existing entry's [HistoryEntity.id] is preserved.
     */
    @Query(
        """
        INSERT INTO history(id, chapter_id, manga_id, read_at)
        VALUES (:id, :chapterId, :mangaId, :readAt)
        ON CONFLICT(chapter_id) DO UPDATE SET read_at = excluded.read_at
        """,
    )
    suspend fun upsertHistory(
        id: Uuid,
        chapterId: Uuid,
        mangaId: Uuid,
        readAt: Instant,
    )

    @Query("DELETE FROM history WHERE id = :id")
    suspend fun deleteById(id: Uuid)

    @Query("DELETE FROM history WHERE manga_id = :mangaId")
    suspend fun deleteForManga(mangaId: Uuid)

    @Query("DELETE FROM history")
    suspend fun clear()
}
