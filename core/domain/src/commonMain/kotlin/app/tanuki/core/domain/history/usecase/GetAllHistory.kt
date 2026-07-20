package app.tanuki.core.domain.history.usecase

import app.tanuki.core.domain.history.model.ChapterHistory
import app.tanuki.core.domain.history.repository.HistoryRepository
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant.Companion.DISTANT_PAST

/**
 * Supplies the user's *entire* reading history, unbounded by period, most-recent first.
 *
 * Distinct from [GetHistory], which scopes to the current calendar week/month/year for the Activity
 * screen. This use case backs the standalone "overall history" view; keeping the two separate lets the
 * [app.tanuki.core.domain.history.model.TimePeriod] enum stay strictly the three real periods.
 */
sealed interface GetAllHistory {
    operator fun invoke(): Flow<List<ChapterHistory>>

    class Default(
        private val historyRepository: HistoryRepository
    ) : GetAllHistory {
        override fun invoke(): Flow<List<ChapterHistory>> = historyRepository.observeHistory(DISTANT_PAST)
    }
}