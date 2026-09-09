package app.tanuki.core.domain.history.model

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class HistoryRetentionTest {
    private val utc = TimeZone.UTC

    /** A zone west of UTC, so a start-of-day boundary lands at a different instant than UTC's. */
    private val newYork = TimeZone.of("America/New_York")

    private fun startOfDay(
        year: Int,
        month: Int,
        day: Int,
        zone: TimeZone = utc
    ): Instant = LocalDate(year, month, day).atStartOfDayIn(zone)

    // ---- the cutoff itself --------------------------------------------------

    @Test
    fun `cutoff is the first instant of the year before last`() {
        assertEquals(
            startOfDay(2024, 1, 1),
            historyRetentionCutoff(utc, LocalDateTime(2026, 9, 9, 15, 30).toInstant(utc))
        )
    }

    @Test
    fun `cutoff is resolved in the given zone rather than UTC`() {
        val now = LocalDateTime(2026, 9, 9, 15, 30).toInstant(utc)
        assertEquals(startOfDay(2024, 1, 1, newYork), historyRetentionCutoff(newYork, now))
    }

    @Test
    fun `the calendar year is the one local to the zone`() {
        // 2026-01-01 00:30 UTC is still 2025-12-31 in New York, so the two zones disagree on the year
        // and therefore on the cutoff — which is the whole reason the zone is a parameter.
        val newYearInUtc = LocalDateTime(2026, 1, 1, 0, 30).toInstant(utc)
        assertEquals(startOfDay(2024, 1, 1), historyRetentionCutoff(utc, newYearInUtc))
        assertEquals(startOfDay(2023, 1, 1, newYork), historyRetentionCutoff(newYork, newYearInUtc))
    }

    // ---- the load-bearing property -----------------------------------------
    //
    // Every period-scoped query binds a lower bound, and the oldest of them is YEAR's previous start.
    // If the cutoff ever rose above it, GetMostRead's year-over-year baseline would be reading a window
    // the sweep had already emptied — a wrong trend arrow rather than a coarser one.

    @Test
    fun `cutoff never rises above the oldest bound any period-scoped query binds`() {
        val zones = listOf(utc, newYork)
        val moments =
            listOf(
                LocalDateTime(2026, 1, 1, 0, 0),      // first instant of a year
                LocalDateTime(2026, 6, 15, 12, 0),    // mid-year
                LocalDateTime(2026, 12, 31, 23, 59),  // last minute of a year
                LocalDateTime(2028, 2, 29, 12, 0),    // a leap day
                LocalDateTime(2028, 12, 31, 23, 59),  // last minute of a leap year
                LocalDateTime(2029, 1, 1, 0, 0),      // first instant after one
            )
        for (zone in zones) {
            for (moment in moments) {
                val now = moment.toInstant(zone)
                val cutoff = historyRetentionCutoff(zone, now)
                val oldestBound = TimePeriod.YEAR.previousStartInstant(zone, now)
                assertTrue(
                    cutoff <= oldestBound,
                    "cutoff $cutoff must not be after $oldestBound (zone=$zone, now=$now)"
                )
            }
        }
    }

    @Test
    fun `cutoff keeps at least two calendar years`() {
        // The narrowest the window ever gets is on Jan 1, where it is exactly two years.
        val newYear = LocalDateTime(2027, 1, 1, 0, 0).toInstant(utc)
        assertEquals(startOfDay(2025, 1, 1), historyRetentionCutoff(utc, newYear))
    }
}
