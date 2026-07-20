package app.tanuki.core.domain.history.model

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

class TimePeriodTest {
    private val zone = TimeZone.UTC

    /** Midnight (UTC) at the given calendar date — the same boundary shape the code produces. */
    private fun startOfDay(
        year: Int,
        month: Int,
        day: Int
    ): Instant = LocalDate(year, month, day).atStartOfDayIn(zone)

    /** Fixed "now": Tuesday 2024-01-16, mid-afternoon, so the date is unambiguous. */
    private val tuesdayAfternoon: Instant =
        LocalDateTime(2024, 1, 16, 15, 30).toInstant(zone)

    // ---- startInstant -------------------------------------------------------

    @Test
    fun `week start is the most recent Sunday`() {
        // 2024-01-16 is a Tuesday; the ongoing week began Sunday 2024-01-14.
        assertEquals(
            startOfDay(2024, 1, 14),
            TimePeriod.WEEK.startInstant(zone, tuesdayAfternoon)
        )
    }

    @Test
    fun `week start on a Sunday is that same Sunday`() {
        // On Sunday itself, the boundary must not roll back a full week.
        val sunday = LocalDateTime(2024, 1, 14, 9, 0).toInstant(zone)
        assertEquals(
            startOfDay(2024, 1, 14),
            TimePeriod.WEEK.startInstant(zone, sunday)
        )
    }

    @Test
    fun `week start on a Saturday is the previous Sunday`() {
        // Saturday 2024-01-20 is the last day of the Sunday-started week begun 2024-01-14.
        val saturday = LocalDateTime(2024, 1, 20, 23, 59).toInstant(zone)
        assertEquals(
            startOfDay(2024, 1, 14),
            TimePeriod.WEEK.startInstant(zone, saturday)
        )
    }

    @Test
    fun `month start is the first of the current month`() {
        assertEquals(
            startOfDay(2024, 1, 1),
            TimePeriod.MONTH.startInstant(zone, tuesdayAfternoon)
        )
    }

    @Test
    fun `year start is January 1st of the current year`() {
        assertEquals(
            startOfDay(2024, 1, 1),
            TimePeriod.YEAR.startInstant(zone, tuesdayAfternoon)
        )
    }

    // ---- previousStartInstant ----------------------------------------------

    @Test
    fun `previous week start is one week before the current one`() {
        assertEquals(
            startOfDay(2024, 1, 7),
            TimePeriod.WEEK.previousStartInstant(zone, tuesdayAfternoon)
        )
    }

    @Test
    fun `previous month start crosses the year boundary`() {
        // January's previous month is December of the prior year.
        assertEquals(
            startOfDay(2023, 12, 1),
            TimePeriod.MONTH.previousStartInstant(zone, tuesdayAfternoon)
        )
    }

    @Test
    fun `previous year start is January 1st of the prior year`() {
        assertEquals(
            startOfDay(2023, 1, 1),
            TimePeriod.YEAR.previousStartInstant(zone, tuesdayAfternoon)
        )
    }

    @Test
    fun `previous start is strictly before current start`() {
        for (period in TimePeriod.entries) {
            assertTrue {
                period.previousStartInstant(zone, period.startInstant(zone, tuesdayAfternoon)) <
                    period.startInstant(
                        zone,
                        tuesdayAfternoon
                    )
            }
        }
    }

    // ---- biggerThan ---------------------------------------------------------

    @Test
    fun `instant exactly at the period start is within the period`() {
        val start = TimePeriod.MONTH.startInstant(zone, tuesdayAfternoon)
        assertTrue(start.biggerThan(TimePeriod.MONTH, zone, tuesdayAfternoon))
    }

    @Test
    fun `instant one millisecond before the period start is outside`() {
        val justBefore = TimePeriod.MONTH.startInstant(zone, tuesdayAfternoon) - 1.milliseconds
        assertFalse(justBefore.biggerThan(TimePeriod.MONTH, zone, tuesdayAfternoon))
    }

    @Test
    fun `instant after the period start is within the period`() {
        assertTrue(tuesdayAfternoon.biggerThan(TimePeriod.WEEK, zone, tuesdayAfternoon))
    }

    @Test
    fun `an instant can be within year but outside month`() {
        // Now is March 2024; a January 2024 read is in the current year but not the current month.
        val marchNow = LocalDateTime(2024, 3, 15, 12, 0).toInstant(zone)
        val january = LocalDateTime(2024, 1, 20, 12, 0).toInstant(zone)
        assertTrue(january.biggerThan(TimePeriod.YEAR, zone, marchNow))
        assertFalse(january.biggerThan(TimePeriod.MONTH, zone, marchNow))
    }
}