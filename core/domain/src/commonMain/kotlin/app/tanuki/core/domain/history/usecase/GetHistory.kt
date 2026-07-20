package app.tanuki.core.domain.history.usecase

import app.tanuki.core.domain.history.model.ChapterHistory
import app.tanuki.core.domain.history.model.TimePeriod
import app.tanuki.core.domain.history.model.startInstant
import app.tanuki.core.domain.history.repository.HistoryRepository
import kotlinx.coroutines.flow.Flow

sealed interface GetHistory {
    operator fun invoke(period: TimePeriod): Flow<List<ChapterHistory>>

    class Default(
        private val historyRepository: HistoryRepository
    ) : GetHistory {
        override fun invoke(period: TimePeriod): Flow<List<ChapterHistory>> =
            historyRepository.observeHistory(period.startInstant())
    }
}