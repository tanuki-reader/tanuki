package app.tanuki.core.domain.history.model

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

enum class TimePeriod {
    WEEK,
    MONTH,
    YEAR
}

/**
 * The instant at which the *current* calendar [period] begins — the start of the ongoing week
 * (Sunday), month (1st), or year (Jan 1). Computed in [zone] because "the start of this week" only
 * has meaning in a specific time zone.
 *
 * This is the source of truth for the period boundary: [biggerThan] compares against it, and the
 * data layer passes it as the `read_at >= ?` bind parameter so the filter runs in SQL.
 *
 * Note: WEEK starts on **Sunday** (not the ISO Monday), so e.g. on a Tuesday the boundary is the
 * most recent Sunday — not "7 days ago".
 */
fun TimePeriod.startInstant(
    zone: TimeZone = TimeZone.currentSystemDefault(),
    now: Instant = Clock.System.now()
): Instant {
    val today = now.toLocalDateTime(zone).date
    val startDate =
        when (this) {
            TimePeriod.WEEK -> today.minus(today.dayOfWeek.isoDayNumber % 7, DateTimeUnit.DAY)
            TimePeriod.MONTH -> LocalDate(today.year, today.month, 1)
            TimePeriod.YEAR -> LocalDate(today.year, Month.JANUARY, 1)
        }
    return startDate.atStartOfDayIn(zone)
}

/**
 * The instant at which the *previous* calendar [period] begins — the start of the week/month/year
 * immediately before the current one (e.g. for MONTH, the 1st of last month). Used as the lower bound
 * when a query needs both the current and prior period in one pass — e.g. the most-read leaderboard,
 * which counts reads in `[currentStart, now]` and compares against `[previousStart, currentStart)`.
 */
fun TimePeriod.previousStartInstant(
    zone: TimeZone = TimeZone.currentSystemDefault(),
    now: Instant = Clock.System.now()
): Instant {
    val today = now.toLocalDateTime(zone).date
    val startDate =
        when (this) {
            TimePeriod.WEEK -> {
                today
                    .minus(today.dayOfWeek.isoDayNumber % 7, DateTimeUnit.DAY)
                    .minus(7, DateTimeUnit.DAY)
            }

            TimePeriod.MONTH -> {
                LocalDate(today.year, today.month, 1).minus(1, DateTimeUnit.MONTH)
            }

            TimePeriod.YEAR -> {
                LocalDate(today.year - 1, Month.JANUARY, 1)
            }
        }
    return startDate.atStartOfDayIn(zone)
}

/**
 * True if this instant falls within the *current* calendar [period] — i.e. it is at or after the
 * [start][startInstant] of the ongoing week/month/year.
 */
fun Instant.biggerThan(
    period: TimePeriod,
    zone: TimeZone = TimeZone.currentSystemDefault(),
    now: Instant = Clock.System.now()
): Boolean = this >= period.startInstant(zone, now)

private val DayOfWeek.isoDayNumber: Int
    get() = ordinal + 1