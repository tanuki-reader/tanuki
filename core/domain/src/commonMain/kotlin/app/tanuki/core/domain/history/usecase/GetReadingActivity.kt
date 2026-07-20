package app.tanuki.core.domain.history.usecase

import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.history.model.ChapterHistory
import app.tanuki.core.domain.history.model.ReadingActivityPoint
import app.tanuki.core.domain.history.model.TimePeriod
import app.tanuki.core.domain.history.model.TimePeriod.MONTH
import app.tanuki.core.domain.history.model.TimePeriod.WEEK
import app.tanuki.core.domain.history.model.TimePeriod.YEAR
import app.tanuki.core.domain.history.model.startInstant
import app.tanuki.core.domain.history.repository.HistoryRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/**
 * Use case: the reading curve for a [TimePeriod] — a gap-filled series of chapters-read-per-bucket over
 * the current week/month/year, ready to plot. Days are the bucket for WEEK/MONTH; months for YEAR (so a
 * year is 12 points, not 365).
 *
 * The window is bounded by [TimePeriod.startInstant], so the SQL `read_at >= ?` filter already trims the
 * rows; this use case only groups the survivors into buckets and fills the empty ones with `0`.
 *
 * @see ReadingActivityPoint
 */
sealed interface GetReadingActivity {
    /**
     * @param period The window to chart.
     * @return A reactive, gap-filled series (oldest bucket first), re-emitting whenever the underlying
     *   history changes.
     */
    operator fun invoke(period: TimePeriod): Flow<List<ReadingActivityPoint>>

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val historyRepository: HistoryRepository,
        private val dispatchers: DispatcherProvider
    ) : GetReadingActivity {
        override fun invoke(period: TimePeriod): Flow<List<ReadingActivityPoint>> {
            val zone = TimeZone.currentSystemDefault()
            return historyRepository
                .observeHistory(period.startInstant(zone))
                .map { history -> history.bucketed(period, zone) }
                .flowOn(dispatchers.default)
        }

        /** Groups [this] into the period's buckets and gap-fills the empty ones with a zero count. */
        private fun List<ChapterHistory>.bucketed(
            period: TimePeriod,
            zone: TimeZone
        ): List<ReadingActivityPoint> {
            val today = Clock.System.now().toLocalDateTime(zone).date
            return when (period) {
                WEEK, MONTH -> {
                    val start = period.startInstant(zone).toLocalDateTime(zone).date
                    val counts = groupingBy { it.readAt.toLocalDateTime(zone).date }.eachCount()
                    datesFrom(start, today).map { date -> ReadingActivityPoint(date, counts[date] ?: 0) }
                }

                YEAR -> {
                    val start = LocalDate(today.year, Month.JANUARY, 1)
                    val counts =
                        groupingBy { it.readAt.toLocalDateTime(zone).date.firstOfMonth() }.eachCount()
                    monthsFrom(start, today).map { month -> ReadingActivityPoint(month, counts[month] ?: 0) }
                }
            }
        }

        /** Every day in `[start, end]` inclusive, ascending. */
        private fun datesFrom(
            start: LocalDate,
            end: LocalDate
        ): List<LocalDate> {
            val days = mutableListOf<LocalDate>()
            var cursor = start
            while (cursor <= end) {
                days += cursor
                cursor = cursor.plus(1, DateTimeUnit.DAY)
            }
            return days
        }

        /** The first-of-month for every month in `[start, end]` inclusive, ascending. */
        private fun monthsFrom(
            start: LocalDate,
            end: LocalDate
        ): List<LocalDate> {
            val months = mutableListOf<LocalDate>()
            var cursor = start.firstOfMonth()
            val last = end.firstOfMonth()
            while (cursor <= last) {
                months += cursor
                cursor = cursor.plus(1, DateTimeUnit.MONTH)
            }
            return months
        }

        private fun LocalDate.firstOfMonth(): LocalDate = LocalDate(year, month, 1)
    }
}