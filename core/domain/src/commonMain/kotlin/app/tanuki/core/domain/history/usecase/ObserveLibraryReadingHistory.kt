package app.tanuki.core.domain.history.usecase

import app.tanuki.core.domain.history.model.HistoryEntry
import app.tanuki.core.domain.history.repository.HistoryRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow

/**
 * Use case: what the user was last reading, per **library** series — the "continue reading" shortlist.
 * One [HistoryEntry] per series (its most recent read, joined to the manga and the chapter), ordered
 * most-recently-read first, capped at `limit`.
 *
 * Unlike [ObserveReadHistory], which is the Activity screen's *whole* history and keeps one entry per
 * read, this collapses to one per series and never leaves the library — reading a series straight from
 * a source without adding it is not something to continue.
 *
 * `limit` is the caller's: how many cards a carousel is worth is a presentation decision. It is a
 * parameter rather than a post-filter because it bounds the query itself — the shortlist is resolved
 * in SQL end to end (membership, the per-series collapse, the ordering, the cap and both joins), so
 * nothing larger than the result is ever loaded.
 *
 * @see HistoryEntry
 */
sealed interface ObserveLibraryReadingHistory {
    operator fun invoke(limit: Int): Flow<List<HistoryEntry>>

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val historyRepository: HistoryRepository
    ) : ObserveLibraryReadingHistory {
        // No `flowOn` here: the repository already hops to `io` for the query.
        override fun invoke(limit: Int): Flow<List<HistoryEntry>> = historyRepository.observeLibraryHistory(limit)
    }
}