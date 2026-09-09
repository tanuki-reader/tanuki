package app.tanuki.core.data.repository.history

import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.history.model.ChapterHistory
import app.tanuki.core.domain.history.model.HistoryEntry
import app.tanuki.core.domain.history.repository.HistoryRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Room-backed [HistoryRepository]. History has one row per chapter, so [upsertHistory] refreshes an
 * existing entry's `read_at` rather than inserting a duplicate.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class HistoryRepositoryImp(
    private val historyDao: HistoryDao,
    private val dispatcherProvider: DispatcherProvider
) : HistoryRepository {
    override fun observeHistory(since: Instant): Flow<List<ChapterHistory>> =
        historyDao
            .observeHistorySince(since)
            .map(List<HistoryEntity>::toDomain)
            .flowOn(dispatcherProvider.io)

    override fun observeLibraryHistory(limit: Int): Flow<List<HistoryEntry>> =
        historyDao
            .observeLibraryHistory(limit)
            .map { it.map(LibraryHistoryEntry::toDomain) }
            .flowOn(dispatcherProvider.io)

    override suspend fun upsertHistory(history: ChapterHistory) =
        historyDao.upsertHistory(
            id = history.id,
            chapterId = history.chapterId,
            mangaId = history.mangaId,
            readAt = history.readAt
        )

    override suspend fun deleteHistory(history: ChapterHistory) = historyDao.deleteById(history.id)

    override suspend fun deleteHistoryForManga(mangaId: Uuid) = historyDao.deleteForManga(mangaId)

    override suspend fun clearHistory() = historyDao.clear()

    override suspend fun pruneHistory(before: Instant): Int = historyDao.pruneHistoryBefore(before)
}

private fun List<HistoryEntity>.toDomain() = map(HistoryEntity::toDomain)