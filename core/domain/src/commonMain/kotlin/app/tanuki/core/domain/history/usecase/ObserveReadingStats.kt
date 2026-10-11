package app.tanuki.core.domain.history.usecase

import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.history.model.ChapterHistory
import app.tanuki.core.domain.history.model.ReadingStats
import app.tanuki.core.domain.history.repository.HistoryRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant.Companion.DISTANT_PAST

/**
 * Use case: the user's reading aggregates ([ReadingStats]) — headline totals and the current and best
 * day streaks. Deliberately unscoped by period (the overview describes the history as a whole),
 * complementing the period-scoped [GetReadingActivity] and [GetMostRead].
 *
 * ### What "unscoped" means now that history is pruned
 *
 * This reads from `DISTANT_PAST`, but [PruneHistory] bounds what is *there* to
 * [historyRetentionCutoff][app.tanuki.core.domain.history.model.historyRetentionCutoff] — at least two
 * calendar years — keeping each series' most recent read whatever its age. So the aggregates divide:
 *
 * - [ReadingStats.seriesCount] is **exact, all-time**, and stays so: the retention rule never removes a
 *   series' last row, so a series read once and never again is still counted years later.
 * - [ReadingStats.dayStreak] is unaffected in practice — it walks backwards from *today* and stops at the
 *   first gap, so only a streak longer than the retention window could be truncated by it.
 *   [ReadingStats.bestStreak] only ever sees the retained window.
 * - [ReadingStats.totalChapters] counts rows, so it describes the retained window rather than all time. The Activity
 *   screen's "all time" cue is deliberately left as it is; see the item 2.3 write-up in
 *   `docs/backlog-efficiency.md` for why re-labelling it would be worse copy than the imprecision.
 *
 * @see ReadingStats
 */
interface ObserveReadingStats {
    operator fun invoke(): Flow<ReadingStats>

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val historyRepository: HistoryRepository,
        private val dispatchers: DispatcherProvider
    ) : ObserveReadingStats {
        override fun invoke(): Flow<ReadingStats> =
            historyRepository
                .observeHistory(DISTANT_PAST)
                .map { it.toStats() }
                .flowOn(dispatchers.io)

        private fun List<ChapterHistory>.toStats(zone: TimeZone = TimeZone.currentSystemDefault()): ReadingStats {
            if (isEmpty()) return ReadingStats.Empty
            val readDays = mapTo(mutableSetOf()) { it.readAt.toLocalDateTime(zone).date }
            return ReadingStats(
                totalChapters = size,
                seriesCount = distinctBy(ChapterHistory::mangaId).size,
                dayStreak = currentStreak(readDays, Clock.System.now().toLocalDateTime(zone).date),
                bestStreak = bestStreak(readDays)
            )
        }
    }
}

/** Consecutive reading days ending [today]; 0 when nothing was read today. */
internal fun currentStreak(
    readDays: Set<LocalDate>,
    today: LocalDate
): Int {
    var day = today
    var streak = 0
    while (day in readDays) {
        streak++
        day = day.minus(1, DateTimeUnit.DAY)
    }
    return streak
}

/** The longest run of consecutive days in [readDays]. */
internal fun bestStreak(readDays: Set<LocalDate>): Int {
    var best = 0
    var run = 0
    var previous: LocalDate? = null
    for (day in readDays.sorted()) {
        run = if (previous != null && previous.plus(1, DateTimeUnit.DAY) == day) run + 1 else 1
        best = maxOf(best, run)
        previous = day
    }
    return best
}
