package app.tanuki.features.activity.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.tanuki.assets.designSystem.TanukiTheme
import app.tanuki.assets.designSystem.component.SectionHeader
import app.tanuki.assets.designSystem.component.Segment
import app.tanuki.assets.designSystem.component.TanukiEmptyState
import app.tanuki.assets.designSystem.component.TanukiSegmentedControl
import app.tanuki.core.domain.history.model.TimePeriod
import app.tanuki.features.activity.composable.components.ActivityBarChart
import app.tanuki.features.activity.composable.components.MostReadRow
import app.tanuki.features.activity.composable.components.StatTile
import app.tanuki.features.activity.resources.Res
import app.tanuki.features.activity.resources.activity_all_time
import app.tanuki.features.activity.resources.activity_chapters
import app.tanuki.features.activity.resources.activity_chapters_per_day
import app.tanuki.features.activity.resources.activity_chapters_per_month
import app.tanuki.features.activity.resources.activity_current_streak
import app.tanuki.features.activity.resources.activity_curve_empty
import app.tanuki.features.activity.resources.activity_day_month
import app.tanuki.features.activity.resources.activity_period_month
import app.tanuki.features.activity.resources.activity_period_week
import app.tanuki.features.activity.resources.activity_period_year
import app.tanuki.features.activity.resources.activity_section_most_read
import app.tanuki.features.activity.resources.activity_stat_best_streak
import app.tanuki.features.activity.resources.activity_stat_chapters
import app.tanuki.features.activity.resources.activity_stat_chapters_month
import app.tanuki.features.activity.resources.activity_stat_chapters_week
import app.tanuki.features.activity.resources.activity_stat_chapters_year
import app.tanuki.features.activity.resources.activity_stat_days_reading
import app.tanuki.features.activity.resources.activity_stat_months_reading
import app.tanuki.features.activity.resources.activity_stat_series
import app.tanuki.features.activity.resources.activity_streak_days
import app.tanuki.features.activity.resources.activity_this_month
import app.tanuki.features.activity.resources.activity_this_week
import app.tanuki.features.activity.resources.activity_this_year
import app.tanuki.features.activity.viewmodel.StatsState
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.uuid.Uuid

/**
 * The Statistics tab: all-time tiles, the period selector, then the period's tiles, per-day bars and
 * most-read leaderboard. Every tile names its period, so "979 chapters read" and "64 chapters read this
 * month" can't be mistaken for each other.
 */
@Composable
internal fun StatisticsView(
    stats: StatsState,
    onPeriodChange: (TimePeriod) -> Unit,
    onOpenSeries: (Uuid) -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = TanukiTheme.spacing
    val year = stats.period == TimePeriod.YEAR
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = spacing.gutter, vertical = spacing.lg),
        verticalArrangement = Arrangement.spacedBy(spacing.xl)
    ) {
        SectionHeader(title = stringResource(Res.string.activity_all_time))
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
            val all = stats.allTime
            StatTile(all.chapters.toString(), stringResource(Res.string.activity_stat_chapters), Modifier.weight(1f))
            StatTile(all.series.toString(), stringResource(Res.string.activity_stat_series), Modifier.weight(1f))
            StatTile(
                value = pluralStringResource(Res.plurals.activity_streak_days, all.bestStreak, all.bestStreak),
                label = stringResource(Res.string.activity_stat_best_streak),
                detail = pluralStringResource(Res.plurals.activity_current_streak, all.currentStreak, all.currentStreak),
                modifier = Modifier.weight(1f)
            )
        }

        TanukiSegmentedControl(
            segments =
                listOf(
                    Segment(TimePeriod.WEEK, stringResource(Res.string.activity_period_week)),
                    Segment(TimePeriod.MONTH, stringResource(Res.string.activity_period_month)),
                    Segment(TimePeriod.YEAR, stringResource(Res.string.activity_period_year))
                ),
            selected = stats.period,
            onSelect = onPeriodChange,
            modifier = Modifier.fillMaxWidth()
        )

        SectionHeader(title = periodTitle(stats.period))
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
            StatTile(stats.periodChapters.toString(), periodChaptersLabel(stats.period), Modifier.weight(1f))
            StatTile(
                value = stats.activeDays.toString(),
                label =
                    pluralStringResource(
                        if (year) Res.plurals.activity_stat_months_reading else Res.plurals.activity_stat_days_reading,
                        stats.elapsedDays,
                        stats.elapsedDays
                    ),
                modifier = Modifier.weight(1f)
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            SectionHeader(
                title = stringResource(if (year) Res.string.activity_chapters_per_month else Res.string.activity_chapters_per_day)
            )
            if (stats.periodChapters == 0) {
                TanukiEmptyState(message = stringResource(Res.string.activity_curve_empty))
            } else {
                val months = monthAbbreviations()
                ActivityBarChart(
                    counts = stats.bars.map { it.count },
                    axisLabels = barAxisLabels(stats.period, stats.bars),
                    selectionLabel = { index ->
                        val bar = stats.bars[index]
                        val date =
                            if (year) months[bar.month - 1] else stringResource(Res.string.activity_day_month, bar.day, months[bar.month - 1])
                        date + " · " + pluralStringResource(Res.plurals.activity_chapters, bar.count, bar.count)
                    }
                )
            }
        }

        if (stats.mostRead.isNotEmpty()) {
            Column {
                SectionHeader(title = stringResource(Res.string.activity_section_most_read))
                stats.mostRead.forEach { item -> MostReadRow(item, onOpenSeries = { onOpenSeries(item.mangaId) }) }
            }
        }
    }
}

@Composable
private fun periodTitle(period: TimePeriod): String =
    stringResource(
        when (period) {
            TimePeriod.WEEK -> Res.string.activity_this_week
            TimePeriod.MONTH -> Res.string.activity_this_month
            TimePeriod.YEAR -> Res.string.activity_this_year
        }
    )

@Composable
private fun periodChaptersLabel(period: TimePeriod): String =
    stringResource(
        when (period) {
            TimePeriod.WEEK -> Res.string.activity_stat_chapters_week
            TimePeriod.MONTH -> Res.string.activity_stat_chapters_month
            TimePeriod.YEAR -> Res.string.activity_stat_chapters_year
        }
    )
