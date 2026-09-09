package app.tanuki.core.domain.history.repository

import app.tanuki.core.domain.history.model.ChapterHistory
import app.tanuki.core.domain.history.model.HistoryEntry
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant
import kotlin.uuid.Uuid

interface HistoryRepository {
    /** Reactive history read at or after [since], most-recent first. */
    fun observeHistory(since: Instant): Flow<List<ChapterHistory>>

    /**
     * The library series the user has read, most-recently-read first — **one entry per series**, each
     * carrying that series' latest read, at most [limit] of them.
     *
     * Resolved entirely in the query, including the join to each entry's manga and chapter: this never
     * loads the whole history, the whole library, or any series' chapter list.
     */
    fun observeLibraryHistory(limit: Int): Flow<List<HistoryEntry>>

    suspend fun upsertHistory(history: ChapterHistory)

    suspend fun deleteHistory(history: ChapterHistory)

    suspend fun deleteHistoryForManga(mangaId: Uuid)

    suspend fun clearHistory()

    /**
     * Retention sweep: delete history older than [before], **except** each series' most recent read,
     * which is kept whatever its age.
     *
     * That exemption is not a nicety — a series' newest row is what `last_read` and Home's
     * continue-reading shortlist are both derived from, so dropping it would make an old series look
     * unread rather than merely un-detailed.
     *
     * @return How many rows were deleted; 0 when there was nothing old enough, which is the normal case.
     */
    suspend fun pruneHistory(before: Instant): Int
}