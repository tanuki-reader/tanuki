package app.tanuki.core.domain.history.model

/**
 * All-time reading aggregates for the Activity screen's overview — deliberately **not** scoped to a
 * [TimePeriod] (unlike the reading curve or the most-read leaderboard).
 *
 * @property totalChapters Total chapters ever read (every history row).
 * @property seriesCount Distinct series the user has read at least one chapter of.
 * @property dayStreak Consecutive days up to today on which at least one chapter was read (0 if nothing
 *   was read today — i.e. the streak is broken).
 * @property bestStreak The longest run of consecutive reading days ever, [dayStreak] included.
 */
data class ReadingStats(
    val totalChapters: Int,
    val seriesCount: Int,
    val dayStreak: Int,
    val bestStreak: Int
) {
    companion object {
        val Empty = ReadingStats(totalChapters = 0, seriesCount = 0, dayStreak = 0, bestStreak = 0)
    }
}
