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
}