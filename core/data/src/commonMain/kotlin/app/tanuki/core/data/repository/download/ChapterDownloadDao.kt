package app.tanuki.core.data.repository.download

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import app.tanuki.core.domain.download.model.DownloadError
import app.tanuki.core.domain.download.model.DownloadState
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Room DAO for [ChapterDownloadEntity]. */
@Dao
interface ChapterDownloadDao {
    /** Oldest-queued first — both the queue screen's order and the order the engine works in. */
    @Query(
        "SELECT * FROM chapter_downloads WHERE state IN (:states) ORDER BY queued_at",
    )
    fun observeInStates(states: List<DownloadState>): Flow<List<ChapterDownloadEntity>>

    @Query("SELECT * FROM chapter_downloads WHERE manga_id = :mangaId")
    fun observeForManga(mangaId: Uuid): Flow<List<ChapterDownloadEntity>>

    /**
     * Downloaded chapters per series. Grouped in SQL rather than by folding the rows in Kotlin: this is
     * read by the library grid, where the alternative is streaming every download row in the database
     * to produce a handful of counts.
     */
    @Query(
        "SELECT manga_id AS mangaId, COUNT(*) AS count FROM chapter_downloads " +
            "WHERE state = :state GROUP BY manga_id",
    )
    fun observeCountsInState(state: DownloadState): Flow<List<MangaDownloadCount>>

    @Query("SELECT * FROM chapter_downloads WHERE chapter_id = :chapterId")
    suspend fun get(chapterId: Uuid): ChapterDownloadEntity?

    @Query("SELECT * FROM chapter_downloads")
    suspend fun getAll(): List<ChapterDownloadEntity>

    @Query("SELECT * FROM chapter_downloads WHERE state = :state ORDER BY queued_at LIMIT 1")
    suspend fun firstInState(state: DownloadState): ChapterDownloadEntity?

    /**
     * Enqueues, ignoring chapters already recorded.
     *
     * [OnConflictStrategy.IGNORE] and not an upsert, and this is the whole point of the write: pressing
     * "download" on a chapter that is already downloading must not restart it, and on one that is
     * already downloaded must not reset it to `Queued` and throw its files away.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoring(downloads: List<ChapterDownloadEntity>)

    @Update
    suspend fun update(download: ChapterDownloadEntity)

    /** One narrow row update, called once per page — see `DownloadRepository.setProgress`. */
    @Query(
        "UPDATE chapter_downloads SET downloaded_pages = :downloadedPages, bytes = :bytes " +
            "WHERE chapter_id = :chapterId",
    )
    suspend fun setProgress(
        chapterId: Uuid,
        downloadedPages: Int,
        bytes: Long
    )

    /** Clears `error` as well: a chapter that just completed cannot still carry the reason it once failed. */
    @Query(
        "UPDATE chapter_downloads SET state = :state, page_count = :pageCount, " +
            "downloaded_pages = :pageCount, bytes = :bytes, error = NULL, completed_at = :completedAt " +
            "WHERE chapter_id = :chapterId",
    )
    suspend fun markDownloaded(
        chapterId: Uuid,
        state: DownloadState,
        pageCount: Int,
        bytes: Long,
        completedAt: Instant
    )

    @Query("UPDATE chapter_downloads SET state = :state, error = :error WHERE chapter_id = :chapterId")
    suspend fun markFailed(
        chapterId: Uuid,
        state: DownloadState,
        error: DownloadError
    )

    /** @return how many rows moved — see `DownloadRepository.requeueInterrupted`. */
    @Query("UPDATE chapter_downloads SET state = :to WHERE state = :from")
    suspend fun moveState(
        from: DownloadState,
        to: DownloadState
    ): Int

    @Query("DELETE FROM chapter_downloads WHERE chapter_id IN (:chapterIds)")
    suspend fun delete(chapterIds: List<Uuid>)

    @Query("DELETE FROM chapter_downloads WHERE manga_id = :mangaId")
    suspend fun deleteForManga(mangaId: Uuid)

    @Query("DELETE FROM chapter_downloads")
    suspend fun deleteAll()
}

/** One row of [ChapterDownloadDao.observeCountsInState]. */
data class MangaDownloadCount(
    @ColumnInfo(name = "mangaId") val mangaId: Uuid,
    @ColumnInfo(name = "count") val count: Int,
)
