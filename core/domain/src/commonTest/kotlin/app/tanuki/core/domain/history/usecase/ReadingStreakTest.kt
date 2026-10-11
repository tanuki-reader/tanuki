package app.tanuki.core.domain.history.usecase

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/** The two streaks the Statistics tile shows: the run up to today, and the longest ever. */
class ReadingStreakTest {
    private val today = LocalDate(2026, 10, 11)

    @Test
    fun `a broken streak is 0 while the best one survives`() {
        val days = setOf(LocalDate(2026, 10, 1), LocalDate(2026, 10, 2), LocalDate(2026, 10, 3), LocalDate(2026, 10, 9))

        assertEquals(0, currentStreak(days, today))
        assertEquals(3, bestStreak(days))
    }

    @Test
    fun `a streak through today counts in both - across a month boundary`() {
        val days = setOf(LocalDate(2026, 9, 30), LocalDate(2026, 10, 1), LocalDate(2026, 10, 10), LocalDate(2026, 10, 11))

        assertEquals(2, currentStreak(days, today))
        assertEquals(2, bestStreak(days))
        assertEquals(0, bestStreak(emptySet()))
    }
}

/** The previous period's leaderboard positions, which the "Up 2" / "New" words are computed from. */
class LeaderboardRankTest {
    @Test
    fun `ranks follow read counts - most read first`() {
        val a = kotlin.uuid.Uuid.random()
        val b = kotlin.uuid.Uuid.random()
        val c = kotlin.uuid.Uuid.random()

        assertEquals(mapOf(b to 1, c to 2, a to 3), mapOf(a to 1, b to 9, c to 4).ranked())
    }
}
