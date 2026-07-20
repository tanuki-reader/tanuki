package app.tanuki.core.domain.history.repository

import app.tanuki.core.domain.history.model.ChapterHistory
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant
import kotlin.uuid.Uuid

interface HistoryRepository {
    /** Reactive history read at or after [since], most-recent first. */
    fun observeHistory(since: Instant): Flow<List<ChapterHistory>>

    suspend fun upsertHistory(history: ChapterHistory)

    suspend fun deleteHistory(history: ChapterHistory)

    suspend fun deleteHistoryForManga(mangaId: Uuid)

    suspend fun clearHistory()
}