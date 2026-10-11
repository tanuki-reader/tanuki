package app.tanuki.features.activity.viewmodel

import app.tanuki.core.domain.history.model.TimePeriod
import kotlin.uuid.Uuid

/**
 * The Activity screen: reading history and statistics. Plain values only (no domain `Manga`/`Text`), so
 * SwiftUI and Compose draw the same state; each platform words the numbers itself.
 */
sealed interface ActivityState {
    data object Loading : ActivityState

    data class Loaded(
        val tab: ActivityTab,
        val history: List<HistoryDay>,
        val stats: StatsState
    ) : ActivityState
}

enum class ActivityTab { History, Statistics }

/**
 * One day of history, newest first. [daysAgo] is 0 for today; the date is for days further back, which
 * the UI shows as a date rather than "N days ago".
 */
data class HistoryDay(
    val daysAgo: Int,
    val year: Int,
    val month: Int,
    val day: Int,
    val rows: List<HistoryRow>
)

/**
 * Every chapter of one series read on one day, as one row ("Eleceed · 4 chapters · Ch. 418–421 · 23:40").
 *
 * @property key stable across updates: the series and the day.
 * @property firstChapter / [lastChapter] the lowest and highest chapter number read that day, displayed;
 *   equal for a single chapter, `null` when the chapters have no numbers.
 * @property hour / [minute] when the latest of them was read, local time.
 * @property resumeChapterId the latest-read chapter, where tapping the row continues; `null` if it's gone.
 */
data class HistoryRow(
    val key: String,
    val mangaId: Uuid,
    val title: String,
    val coverUrl: String?,
    val coverHeaders: Map<String, String>,
    val chapterCount: Int,
    val firstChapter: String?,
    val lastChapter: String?,
    val hour: Int,
    val minute: Int,
    val resumeChapterId: Uuid?
)

/**
 * Statistics: all-time totals, then the selected [period]'s numbers.
 *
 * @property periodChapters chapters read in the period so far.
 * @property activeDays / [elapsedDays] reading days out of the period's days so far ("8 of 11 days") —
 *   **months** for [TimePeriod.YEAR], like [bars].
 * @property bars one per day (one per month for a year), oldest first, gaps included.
 */
data class StatsState(
    val allTime: AllTimeStats,
    val period: TimePeriod,
    val periodChapters: Int,
    val activeDays: Int,
    val elapsedDays: Int,
    val bars: List<ActivityBar>,
    val mostRead: List<RankedSeries>
)

/** [currentStreak] is 0 once a day is missed; [bestStreak] is the longest ever. */
data class AllTimeStats(
    val chapters: Int,
    val series: Int,
    val currentStreak: Int,
    val bestStreak: Int
)

/** [day] is 1 for a year's month bars. */
data class ActivityBar(
    val year: Int,
    val month: Int,
    val day: Int,
    val count: Int
)

data class RankedSeries(
    val rank: Int,
    val mangaId: Uuid,
    val title: String,
    val coverUrl: String?,
    val coverHeaders: Map<String, String>,
    val count: Int,
    val change: RankChange
)

/** How a series' rank moved since the previous period — in words, not just an arrow's colour. */
sealed interface RankChange {
    data object New : RankChange

    data object Same : RankChange

    data class Up(
        val places: Int
    ) : RankChange

    data class Down(
        val places: Int
    ) : RankChange
}
