package app.tanuki.core.domain.history.usecase

import app.tanuki.core.domain.common.service.RecordingLogger
import app.tanuki.core.domain.history.model.ChapterHistory
import app.tanuki.core.domain.history.model.historyRetentionCutoff
import app.tanuki.core.domain.history.repository.HistoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

class PruneHistoryTest {
    @Test
    fun `prunes at the derived retention cutoff`() = runTest {
        val repo = RecordingHistoryRepo(deleted = 0)
        PruneHistory.Default(repo, RecordingLogger())()

        assertEquals(1, repo.prunedBefore.size, "the sweep runs exactly once")
        // Recomputed rather than hardcoded: the point is that the use case derives its window rather
        // than inventing one. The date arithmetic itself is HistoryRetentionTest's job.
        assertEquals(historyRetentionCutoff(), repo.prunedBefore.single())
    }

    @Test
    fun `returns how many rows were dropped`() = runTest {
        val repo = RecordingHistoryRepo(deleted = 412)
        assertEquals(412, PruneHistory.Default(repo, RecordingLogger())())
    }

    @Test
    fun `logs what it dropped`() = runTest {
        val logger = RecordingLogger()
        PruneHistory.Default(RecordingHistoryRepo(deleted = 412), logger)()

        assertEquals(1, logger.entries.size)
        assertTrue(
            logger.entries.single().message.orEmpty().contains("412"),
            "the count is the only thing worth logging: ${logger.entries.single().message}"
        )
    }

    @Test
    fun `is silent when there was nothing old enough`() = runTest {
        // Every launch until an install is two calendar years old. A line per start would bury the ones
        // that mean something, so "nothing to do" must log nothing at all.
        val logger = RecordingLogger()
        PruneHistory.Default(RecordingHistoryRepo(deleted = 0), logger)()

        assertEquals(emptyList(), logger.entries)
    }
}

/**
 * Records the cutoff it was handed. The keep-each-series'-latest rule is a SQL predicate and cannot be
 * reached from here — see [PruneHistory]'s docs and the manual check in `docs/backlog-efficiency.md`.
 */
private class RecordingHistoryRepo(private val deleted: Int) : HistoryRepository {
    val prunedBefore = mutableListOf<Instant>()

    override suspend fun pruneHistory(before: Instant): Int {
        prunedBefore += before
        return deleted
    }

    override fun observeHistory(since: Instant): Flow<List<ChapterHistory>> = error("unused")
    override fun observeLibraryHistory(limit: Int) = error("unused")
    override suspend fun upsertHistory(history: ChapterHistory) = error("unused")
    override suspend fun deleteHistory(history: ChapterHistory) = error("unused")
    override suspend fun deleteHistoryForManga(mangaId: Uuid) = error("unused")
    override suspend fun clearHistory() = error("unused")
}
