package app.tanuki.core.domain.history.usecase

import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.domain.common.service.info
import app.tanuki.core.domain.history.model.historyRetentionCutoff
import app.tanuki.core.domain.history.repository.HistoryRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlin.time.Instant

/**
 * Use case: keep the history table bounded. Run once at launch.
 *
 * Nothing else in the app ever removes a history row on age. Rows arrive one per *distinct chapter*
 * (opening an already-read chapter refreshes its timestamp rather than inserting), so the table grows
 * slowly but monotonically for the life of the install — and the two readers that take no period walk
 * every row of it on every chapter the reader opens.
 *
 * ### The rule, in one sentence
 *
 * Delete rows older than [historyRetentionCutoff], **except** each series' most recent read.
 *
 * The exemption is what makes the sweep lossless where it matters: a series' newest row is what
 * `last_read` (the library's sort-by-last-read, the detail screen) and Home's continue-reading shortlist
 * are both derived from, so pruning it would not make an old series less detailed — it would make it
 * look unread. It also keeps `seriesCount` in
 * [ReadingStats][app.tanuki.core.domain.history.model.ReadingStats] exact forever. The predicate itself
 * lives in SQL, in [app.tanuki.core.domain.history.repository.HistoryRepository.pruneHistory]'s DAO
 * query, because doing it above the database would mean loading the whole table — the cost this exists
 * to remove.
 *
 * ### Why it is safe to run on every launch, ungated
 *
 * Room invalidates from per-row triggers, so a DELETE that matches nothing fires none and re-runs no
 * query: on the common launch this costs one indexed scan and invalidates nothing. A "prune at most
 * once a day" gate — a `last_history_prune` preference, in the shape of `last_library_update` — would
 * be state to maintain in exchange for nothing.
 *
 * It is deliberately **not** hung off the write path.
 * [app.tanuki.core.domain.chapter.usecase.UpdateReadingProgress] already records why for its own sweep:
 * that path runs on every page turn, and a database walk between pages is exactly what must not happen.
 */
sealed interface PruneHistory {
    /** @return How many rows were dropped; 0 on any install younger than the retention window. */
    suspend operator fun invoke(): Int

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val historyRepository: HistoryRepository,
        private val logger: Logger,
    ) : PruneHistory {
        override suspend fun invoke(): Int {
            val cutoff: Instant = historyRetentionCutoff()
            val pruned = historyRepository.pruneHistory(cutoff)
            // Silent when there was nothing to drop, which is every launch until an install is two
            // calendar years old — a line per start would bury the ones that mean something.
            if (pruned > 0) logger.info(TAG, "Pruned $pruned history rows older than $cutoff")
            return pruned
        }

        private companion object {
            const val TAG = "PruneHistory"
        }
    }
}
