package app.tanuki.features.activity.composable

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.tanuki.assets.designSystem.TanukiTheme
import app.tanuki.assets.designSystem.component.TanukiEmptyState
import app.tanuki.features.activity.composable.components.SwipeableHistoryRow
import app.tanuki.features.activity.resources.Res
import app.tanuki.features.activity.resources.activity_action_delete
import app.tanuki.features.activity.resources.activity_action_series
import app.tanuki.features.activity.resources.activity_day_month
import app.tanuki.features.activity.resources.activity_day_today
import app.tanuki.features.activity.resources.activity_day_yesterday
import app.tanuki.features.activity.resources.activity_days_ago
import app.tanuki.features.activity.resources.activity_history_empty
import app.tanuki.features.activity.viewmodel.HistoryDay
import app.tanuki.features.activity.viewmodel.HistoryRow
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The History tab: one section per day, newest first, each row a series read that day. Rows swipe left
 * to reveal "series" and "delete"; a tap continues reading.
 */
@Composable
internal fun HistoryList(
    days: List<HistoryDay>,
    onRowClick: (HistoryRow) -> Unit,
    onOpenSeries: (HistoryRow) -> Unit,
    onDeleteRow: (HistoryRow) -> Unit,
    modifier: Modifier = Modifier
) {
    if (days.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            TanukiEmptyState(message = stringResource(Res.string.activity_history_empty))
        }
        return
    }
    val seriesLabel = stringResource(Res.string.activity_action_series)
    val deleteLabel = stringResource(Res.string.activity_action_delete)

    LazyColumn(modifier = modifier.fillMaxSize()) {
        days.forEach { day ->
            item(key = "day-${day.year}-${day.month}-${day.day}") {
                Text(
                    text = dayLabel(day),
                    style = TanukiTheme.typography.label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier =
                        Modifier
                            .animateItem()
                            .padding(
                                start = TanukiTheme.spacing.gutter,
                                end = TanukiTheme.spacing.gutter,
                                top = TanukiTheme.spacing.lg,
                                bottom = TanukiTheme.spacing.xs
                            )
                )
            }
            items(day.rows, key = { it.key }) { row ->
                SwipeableHistoryRow(
                    row = row,
                    onOpenChapter = { onRowClick(row) },
                    onOpenSeries = { onOpenSeries(row) },
                    onDelete = { onDeleteRow(row) },
                    seriesLabel = seriesLabel,
                    deleteLabel = deleteLabel,
                    modifier = Modifier.animateItem()
                )
            }
        }
    }
}

/** "Today", "Yesterday", "3 days ago", then the date ("Oct 6") once it's a week or more back. */
@Composable
internal fun dayLabel(day: HistoryDay): String =
    when {
        day.daysAgo <= 0 -> stringResource(Res.string.activity_day_today)
        day.daysAgo == 1 -> stringResource(Res.string.activity_day_yesterday)
        day.daysAgo < 7 -> pluralStringResource(Res.plurals.activity_days_ago, day.daysAgo, day.daysAgo)
        else -> stringResource(Res.string.activity_day_month, day.day, monthAbbreviations()[day.month - 1])
    }
