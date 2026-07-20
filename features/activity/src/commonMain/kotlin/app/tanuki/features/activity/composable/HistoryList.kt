package app.tanuki.features.activity.composable

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.tanuki.assets.designSystem.TanukiTheme
import app.tanuki.assets.designSystem.component.TanukiEmptyState
import app.tanuki.core.domain.history.model.HistoryEntry
import app.tanuki.features.activity.composable.components.SwipeableHistoryRow
import app.tanuki.features.activity.resources.Res
import app.tanuki.features.activity.resources.activity_action_delete
import app.tanuki.features.activity.resources.activity_action_series
import org.jetbrains.compose.resources.stringResource

/**
 * The History tab body: a scrolling, most-recent-first list of read chapters led by a [countLabel]
 * caption, or a centered [emptyMessage] when nothing has been read yet. Each row swipes left to reveal
 * "series" (open the manga) and "delete" actions; a tap opens the chapter.
 *
 * [countLabel] and [emptyMessage] are UI copy — the caller passes **already-resolved** strings
 * (`stringResource(...)`), never raw resource ids, so this stays independent of the resource index.
 */
@Composable
internal fun HistoryList(
    entries: List<HistoryEntry>,
    countLabel: String,
    emptyMessage: String,
    onEntryClick: (HistoryEntry) -> Unit,
    onOpenSeries: (HistoryEntry) -> Unit,
    onDeleteEntry: (HistoryEntry) -> Unit,
    modifier: Modifier = Modifier
) {
    if (entries.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            TanukiEmptyState(message = emptyMessage)
        }
        return
    }

    LazyColumn(modifier = modifier.fillMaxSize()) {
        item {
            Text(
                text = countLabel,
                style = TanukiTheme.typography.caption,
                color = TanukiTheme.colors.textFaint,
                modifier =
                    Modifier.padding(
                        horizontal = TanukiTheme.spacing.gutter,
                        vertical = TanukiTheme.spacing.sm
                    )
            )
        }
        items(items = entries, key = { it.id }) { entry ->
            SwipeableHistoryRow(
                entry = entry,
                onOpenChapter = { onEntryClick(entry) },
                onOpenSeries = { onOpenSeries(entry) },
                onDelete = { onDeleteEntry(entry) },
                seriesLabel = stringResource(Res.string.activity_action_series),
                deleteLabel = stringResource(Res.string.activity_action_delete)
            )
        }
    }
}