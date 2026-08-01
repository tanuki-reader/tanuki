package app.tanuki.features.manga.composable

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tanuki.assets.designSystem.TanukiTheme
import app.tanuki.assets.designSystem.component.CheckIcon
import app.tanuki.assets.designSystem.component.CheckOffIcon
import app.tanuki.assets.designSystem.component.DoubleArrowDownIcon
import app.tanuki.assets.designSystem.component.DoubleArrowUpIcon
import app.tanuki.features.manga.resources.Res
import app.tanuki.features.manga.resources.manga_selection_mark_read
import app.tanuki.features.manga.resources.manga_selection_mark_unread
import app.tanuki.features.manga.resources.manga_selection_select_above
import app.tanuki.features.manga.resources.manga_selection_select_below
import app.tanuki.features.manga.viewmodel.MangaState.ChapterSelection
import org.jetbrains.compose.resources.stringResource

/** Minimum height of one action cell — comfortably past the 44dp tap-target floor. */
private val ActionMinHeight = 56.dp

/** How far a disabled action fades. Legible enough to read as "not now", not as missing. */
private const val DISABLED_ALPHA = 0.35f

/**
 * The bulk-action bar the Chapters tab swaps in for its "Read" button while chapters are selected.
 *
 * It carries all four selection verbs in one thumb-reachable row, because they're used together: extend
 * the range up or down, then mark the result read or unread. Putting the range extenders *here* rather
 * than in the top bar keeps the whole flow at the bottom of a phone — the list is scrolled with the thumb,
 * and reaching up to the app bar between two extensions would break that.
 *
 * Actions that would do nothing are shown disabled rather than hidden (nothing above the top row to
 * select; everything already read), so the bar keeps a stable shape and the user learns what the actions
 * mean instead of watching them appear and vanish.
 *
 * @param selection what is picked and what may be done with it — the `can*` flags drive the enabled state.
 * @param onSelectAbove extend the selection to everything above it in the list.
 * @param onSelectBelow extend the selection to everything below it.
 * @param onMarkRead mark every picked chapter read.
 * @param onMarkUnread mark every picked chapter unread.
 */
@Composable
internal fun ChapterSelectionBar(
    selection: ChapterSelection,
    onSelectAbove: () -> Unit,
    onSelectBelow: () -> Unit,
    onMarkRead: () -> Unit,
    onMarkUnread: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = TanukiTheme.spacing.xs, vertical = TanukiTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SelectionAction(
                label = stringResource(Res.string.manga_selection_select_above),
                enabled = selection.canSelectAbove,
                onClick = onSelectAbove,
                modifier = Modifier.weight(1f)
            ) { tint -> DoubleArrowUpIcon(tint = tint) }
            SelectionAction(
                label = stringResource(Res.string.manga_selection_select_below),
                enabled = selection.canSelectBelow,
                onClick = onSelectBelow,
                modifier = Modifier.weight(1f)
            ) { tint -> DoubleArrowDownIcon(tint = tint) }
            SelectionAction(
                label = stringResource(Res.string.manga_selection_mark_read),
                enabled = selection.canMarkRead,
                onClick = onMarkRead,
                modifier = Modifier.weight(1f)
            ) { tint -> CheckIcon(tint = tint, size = 22.dp) }
            SelectionAction(
                label = stringResource(Res.string.manga_selection_mark_unread),
                enabled = selection.canMarkUnread,
                onClick = onMarkUnread,
                modifier = Modifier.weight(1f)
            ) { tint -> CheckOffIcon(tint = tint, size = 22.dp) }
        }
    }
}

/**
 * One glyph-over-label action cell. The label is always rendered — an icon-only bar would leave "which
 * double arrow was which" to guesswork, and these four verbs are not ones the user meets often enough to
 * memorize. It doubles as the accessibility name, so no separate description is needed.
 */
@Composable
private fun SelectionAction(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable (tint: Color) -> Unit
) {
    val tint = MaterialTheme.colorScheme.onSurface
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(TanukiTheme.spacing.xxs, Alignment.CenterVertically),
        modifier =
            modifier
                .heightIn(min = ActionMinHeight)
                .clickable(enabled = enabled, onClick = onClick)
                // Merged so the glyph and its label are one focusable control, read once, rather than the
                // screen reader stopping on each half of the same button.
                .semantics(mergeDescendants = true) { contentDescription = label }
                .alpha(if (enabled) 1f else DISABLED_ALPHA)
                .padding(horizontal = TanukiTheme.spacing.xxs, vertical = TanukiTheme.spacing.xs)
    ) {
        icon(tint)
        Text(
            text = label,
            style = TanukiTheme.typography.caption,
            color = tint,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
