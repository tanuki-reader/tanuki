package app.tanuki.core.domain.history.model

import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The oldest instant history is kept back to: **the first instant of the year before last**, in [zone].
 *
 * The window is *derived, not chosen*. Every period-scoped history query binds a lower bound, and the
 * oldest one any of them can ask for is [previousStartInstant] of [TimePeriod.YEAR] — Jan 1 of *last*
 * year, which the most-read leaderboard uses as the baseline it compares this year against. Anchoring a
 * further calendar year back puts this cutoff at or below that bound on every date of every year, leap
 * years and Dec 31 included, so [GetMostRead][app.tanuki.core.domain.history.usecase.GetMostRead],
 * [GetReadingActivity][app.tanuki.core.domain.history.usecase.GetReadingActivity] and
 * [GetHistory][app.tanuki.core.domain.history.usecase.GetHistory] cannot lose a row they would have
 * read. In practice that keeps 24 to 36 months depending on the date.
 *
 * A flat twelve months was rejected for exactly that reason: it sits *above* the YEAR baseline for most
 * of the year, which would not make the Year view coarser — it would make its trend arrows wrong.
 *
 * Computed as a calendar date through [atStartOfDayIn] rather than by subtracting a [kotlin.time.Duration]:
 * the first instant of a day is not always midnight, and Brazil — one of the two shipped locales — used
 * to start DST at midnight.
 *
 * The two readers that *are* bounded by this are the ones with no period at all — the Activity History
 * list and [ReadingStats][app.tanuki.core.domain.history.model.ReadingStats]. See
 * [app.tanuki.core.domain.history.usecase.ObserveReadingStats] for which of its aggregates that touches
 * and which the retention rule keeps exact.
 */
fun historyRetentionCutoff(
    zone: TimeZone = TimeZone.currentSystemDefault(),
    now: Instant = Clock.System.now(),
): Instant = LocalDate(now.toLocalDateTime(zone).date.year - 2, Month.JANUARY, 1).atStartOfDayIn(zone)
