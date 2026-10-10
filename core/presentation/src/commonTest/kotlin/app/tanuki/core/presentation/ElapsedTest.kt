package app.tanuki.core.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class ElapsedTest {
    private val now = Instant.fromEpochSeconds(1_800_000_000)

    private fun ago(duration: Duration) = elapsedSince(now - duration, now)

    @Test
    fun `each bucket starts where the previous one ends`() {
        assertEquals(Elapsed(ElapsedUnit.JustNow, 0), ago(59.seconds))
        assertEquals(Elapsed(ElapsedUnit.Minutes, 1), ago(1.minutes))
        assertEquals(Elapsed(ElapsedUnit.Minutes, 59), ago(59.minutes))
        assertEquals(Elapsed(ElapsedUnit.Hours, 1), ago(60.minutes))
        assertEquals(Elapsed(ElapsedUnit.Hours, 23), ago(23.hours + 59.minutes))
        assertEquals(Elapsed(ElapsedUnit.Days, 1), ago(24.hours))
        assertEquals(Elapsed(ElapsedUnit.Days, 6), ago(6.days + 23.hours))
        assertEquals(Elapsed(ElapsedUnit.Weeks, 1), ago(7.days))
        assertEquals(Elapsed(ElapsedUnit.Weeks, 4), ago(29.days))
    }

    @Test
    fun `a time in the future reads as just now`() {
        assertEquals(Elapsed(ElapsedUnit.JustNow, 0), ago((-5).minutes))
    }
}
