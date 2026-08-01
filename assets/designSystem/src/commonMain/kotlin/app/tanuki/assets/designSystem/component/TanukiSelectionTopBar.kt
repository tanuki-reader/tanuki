package app.tanuki.assets.designSystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.tanuki.assets.designSystem.TanukiTheme

/** The design system's app-bar height (`--appbar-h`), shared with [TanukiDetailTopBar]. */
private val AppBarHeight = 56.dp

/**
 * The **contextual** app bar a screen swaps in while a multi-select is active — the counterpart to
 * [TanukiDetailTopBar], replacing it in place rather than stacking on top of it.
 *
 * A selection is a mode, so the bar has to say so at a glance: it is always solid (never the detail bar's
 * dissolved-over-the-hero state), its leading control is a **close** X rather than a back arrow — the tap
 * leaves the selection, not the screen — and the [title] carries the accent, the one place the near-
 * monochrome chrome uses color. Together those are three independent signals, so the mode never rests on
 * color alone.
 *
 * [title] is a resolved count ("3 selected") supplied by the caller, since only the feature knows what is
 * being counted and pluralization is a resource concern. [actions] takes the selection-wide controls
 * (select all, extend the range) as [TanukiIconButton]s.
 *
 * @param title what is selected, already localized and pluralized.
 * @param onClose the X was tapped — clear the selection and leave the mode.
 * @param closeDescription accessibility label for the X (e.g. "Exit selection"), resolved by the caller.
 * @param actions trailing selection controls; empty by default.
 */
@Composable
fun TanukiSelectionTopBar(
    title: String,
    onClose: () -> Unit,
    closeDescription: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val containerColor = MaterialTheme.colorScheme.surface
    val dividerColor = MaterialTheme.colorScheme.outlineVariant

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                // Fill and hairline draw across the whole node so the container also sits behind the
                // status-bar icons; the controls below are inset past it by windowInsetsPadding.
                .drawBehind {
                    drawRect(containerColor)
                    drawLine(
                        color = dividerColor,
                        start = Offset(0f, size.height),
                        end = Offset(size.width, size.height),
                        strokeWidth = 1.dp.toPx()
                    )
                }
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(AppBarHeight)
                .padding(horizontal = TanukiTheme.spacing.sm)
    ) {
        TanukiIconButton(
            onClick = onClose,
            modifier = Modifier.semantics { contentDescription = closeDescription }
        ) { tint ->
            CloseIcon(tint = tint, size = 22.dp)
        }
        Text(
            text = title,
            style = TanukiTheme.typography.title,
            color = TanukiTheme.colors.accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = TanukiTheme.spacing.sm)
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TanukiTheme.spacing.xxs),
            content = actions
        )
    }
}

@Composable
@Preview
private fun TanukiSelectionTopBarPreview() {
    TanukiTheme {
        TanukiSelectionTopBar(
            title = "3 selected",
            onClose = {},
            closeDescription = "Exit selection",
            actions = {
                TanukiIconButton(onClick = {}) { tint -> SelectAllIcon(tint = tint) }
            }
        )
    }
}
