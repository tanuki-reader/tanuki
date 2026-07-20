package app.tanuki.core.domain.history.usecase

import app.tanuki.core.domain.history.model.ChapterHistory
import app.tanuki.core.domain.history.model.HistoryEntry
import app.tanuki.core.domain.history.repository.HistoryRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject

/**
 * Use case: remove a single reading-history entry (e.g. the user swiped it away in the Activity list).
 * Deletes only that one read record — the chapter, its progress, and the manga are untouched.
 *
 * @see HistoryEntry
 */
sealed interface DeleteHistory {
    suspend operator fun invoke(entry: HistoryEntry)

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val historyRepository: HistoryRepository
    ) : DeleteHistory {
        override suspend fun invoke(entry: HistoryEntry) =
            historyRepository.deleteHistory(
                ChapterHistory(
                    id = entry.id,
                    chapterId = entry.chapterId,
                    mangaId = entry.manga.id,
                    readAt = entry.readAt
                )
            )
    }
}
