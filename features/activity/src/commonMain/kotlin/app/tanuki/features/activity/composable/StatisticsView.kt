package app.tanuki.features.activity.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.tanuki.assets.designSystem.TanukiTheme
import app.tanuki.assets.designSystem.component.SectionHeader
import app.tanuki.assets.designSystem.component.Segment
import app.tanuki.assets.designSystem.component.TanukiEmptyState
import app.tanuki.assets.designSystem.component.TanukiSegmentedControl
import app.tanuki.core.domain.history.model.MostReadManga
import app.tanuki.core.domain.history.model.ReadingActivityPoint
import app.tanuki.core.domain.history.model.ReadingStats
import app.tanuki.core.domain.history.model.TimePeriod
import app.tanuki.features.activity.composable.components.DistributionEntry
import app.tanuki.features.activity.composable.components.MostReadRow
import app.tanuki.features.activity.composable.components.ReadingCurveChart
import app.tanuki.features.activity.composable.components.ReadingDistributionDonut
import app.tanuki.features.activity.composable.components.StatTile
import app.tanuki.features.activity.resources.Res
import app.tanuki.features.activity.resources.activity_all_time
import app.tanuki.features.activity.resources.activity_chapters
import app.tanuki.features.activity.resources.activity_curve_empty
import app.tanuki.features.activity.resources.activity_distribution_other
import app.tanuki.features.activity.resources.activity_period_month
import app.tanuki.features.activity.resources.activity_period_week
import app.tanuki.features.activity.resources.activity_period_year
import app.tanuki.features.activity.resources.activity_section_categories
import app.tanuki.features.activity.resources.activity_section_curve
import app.tanuki.features.activity.resources.activity_section_distribution
import app.tanuki.features.activity.resources.activity_section_most_read
import app.tanuki.features.activity.resources.activity_stat_active_days
import app.tanuki.features.activity.resources.activity_stat_active_months
import app.tanuki.features.activity.resources.activity_stat_chapters
import app.tanuki.features.activity.resources.activity_stat_series
import app.tanuki.features.activity.resources.activity_stat_streak
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

private const val MAX_RANKED = 5

/**
 * The Statistics dashboard: all-time headline tiles, then a Week/Month/Year selector scoping the reading
 * curve and the most-read leaderboard, and finally the all-time reading-distribution donut. Period-scoped
 * sections re-derive from the selector; the top tiles and the distribution are all-time (they carry an
 * "all time" cue), per the screen's mixed-scope design.
 *
 * Period selection is owned by the ViewModel (it drives the domain queries), so [period]/[onPeriodChange]
 * are hoisted here.
 */
@Composable
internal fun StatisticsView(
    period: TimePeriod,
    activity: List<ReadingActivityPoint>,
    mostRead: List<MostReadManga>,
    stats: ReadingStats,
    onPeriodChange: (TimePeriod) -> Unit,
    onOpenSeries: (MostReadManga) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = TanukiTheme.spacing.gutter, vertical = TanukiTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(TanukiTheme.spacing.xl)
    ) {
        AllTimeSummary(stats)

        TanukiSegmentedControl(
            segments =
                listOf(
                    Segment(TimePeriod.WEEK, stringResource(Res.string.activity_period_week)),
                    Segment(TimePeriod.MONTH, stringResource(Res.string.activity_period_month)),
                    Segment(TimePeriod.YEAR, stringResource(Res.string.activity_period_year))
                ),
            selected = period,
            onSelect = onPeriodChange,
            modifier = Modifier.fillMaxWidth()
        )

        ReadingCurveSection(period = period, activity = activity)
        MostReadSection(
            mostRead = mostRead,
            onOpenSeries = onOpenSeries
        )

        val otherLabel = stringResource(Res.string.activity_distribution_other)
        DistributionSection(
            title = stringResource(Res.string.activity_section_distribution),
            entries = stats.distribution.map { DistributionEntry(it.manga.title.value, it.count) },
            otherLabel = otherLabel
        )
        DistributionSection(
            title = stringResource(Res.string.activity_section_categories),
            entries = stats.categoryShares.map { DistributionEntry(it.category.name.value, it.count) },
            otherLabel = otherLabel
        )
    }
}

/** All-time headline row: total chapters, distinct series, current day streak. */
@Composable
private fun AllTimeSummary(stats: ReadingStats) {
    Row(horizontalArrangement = Arrangement.spacedBy(TanukiTheme.spacing.md)) {
        StatTile(
            value = stats.totalChapters.toString(),
            label = stringResource(Res.string.activity_stat_chapters),
            modifier = Modifier.weight(1f)
        )
        StatTile(
            value = stats.seriesCount.toString(),
            label = stringResource(Res.string.activity_stat_series),
            modifier = Modifier.weight(1f)
        )
        StatTile(
            value = stats.dayStreak.toString(),
            label = stringResource(Res.string.activity_stat_streak),
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ReadingCurveSection(
    period: TimePeriod,
    activity: List<ReadingActivityPoint>
) {
    val total = activity.sumOf { it.count }
    val activeBuckets = activity.count { it.count > 0 }
    // Buckets are days for week/month, months for year — so the "active" unit follows the period.
    val activeLabel =
        if (period == TimePeriod.YEAR) {
            stringResource(Res.string.activity_stat_active_months)
        } else {
            stringResource(Res.string.activity_stat_active_days)
        }

    Column(verticalArrangement = Arrangement.spacedBy(TanukiTheme.spacing.md)) {
        SectionHeader(title = stringResource(Res.string.activity_section_curve))
        if (total == 0) {
            EmptyCaption()
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(TanukiTheme.spacing.md)) {
                StatTile(
                    value = total.toString(),
                    label = stringResource(Res.string.activity_stat_chapters),
                    modifier = Modifier.weight(1f)
                )
                StatTile(value = activeBuckets.toString(), label = activeLabel, modifier = Modifier.weight(1f))
            }
            ReadingCurveChart(points = activity, labels = curveAxisLabels(period, activity))
        }
    }
}

@Composable
private fun MostReadSection(
    mostRead: List<MostReadManga>,
    onOpenSeries: (MostReadManga) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(TanukiTheme.spacing.xs)) {
        SectionHeader(title = stringResource(Res.string.activity_section_most_read))
        if (mostRead.isEmpty()) {
            EmptyCaption()
        } else {
            mostRead.take(MAX_RANKED).forEachIndexed { index, item ->
                MostReadRow(
                    rank = index + 1,
                    item = item,
                    chaptersLabel = pluralStringResource(Res.plurals.activity_chapters, item.readCount, item.readCount),
                    onOpenSeries = onOpenSeries
                )
            }
        }
    }
}

/** One all-time distribution donut (series or category), titled and marked "all time". */
@Composable
private fun DistributionSection(
    title: String,
    entries: List<DistributionEntry>,
    otherLabel: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(TanukiTheme.spacing.md)) {
        SectionHeader(title = title, trailing = stringResource(Res.string.activity_all_time))
        if (entries.isEmpty()) {
            EmptyCaption()
        } else {
            ReadingDistributionDonut(
                entries = entries,
                otherLabel = otherLabel,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** A centered faint empty message reused by the period-scoped sections when they have no data. */
@Composable
private fun EmptyCaption() {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = TanukiTheme.spacing.xl),
        contentAlignment = Alignment.Center
    ) {
        TanukiEmptyState(message = stringResource(Res.string.activity_curve_empty))
    }
}