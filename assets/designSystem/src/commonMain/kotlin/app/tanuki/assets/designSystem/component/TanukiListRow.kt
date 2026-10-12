package app.tanuki.assets.designSystem.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tanuki.assets.designSystem.TanukiSpacing
import app.tanuki.assets.designSystem.TanukiTheme

/** Minimum row height from the design system's `ListRow` (`min-height: 56`). */
private val ListRowMinHeight = 56.dp

/** Layout constants derived from [TanukiListRow]'s own geometry. */
object TanukiListRowDefaults {
    /**
     * Where a row's title starts: the row's leading padding (`lg`) + a [leadingWidth]-wide leading slot +
     * the gap before the text (`md`). Hand it to [TanukiRowDivider] so the rule between two rows runs
     * under their titles rather than their icons — the grouped-list convention.
     */
    fun dividerInset(leadingWidth: Dp): Dp = TanukiSpacing.lg + leadingWidth + TanukiSpacing.md
}

/**
 * A dense, tappable list item — the **Android (Material) dialect** of the design system's `ListRow`,
 * used for drill-down rows (settings categories, sources, extensions). An optional [leading] slot (an
 * icon/thumbnail), a [title] with an optional [subtitle] beneath it, and an optional [trailing] slot. No
 * chevron: Android rows don't carry one (the design system's dialect rule); the row itself is the target.
 *
 * [title]/[subtitle] are already-resolved strings (content or `stringResource(...)`); the component never
 * inlines display literals.
 *
 * @param title the primary label.
 * @param onClick invoked when the row is tapped; `null` renders a non-interactive row.
 * @param subtitle a secondary line beneath the title (e.g. the current value of the setting).
 * @param leading a leading icon/thumbnail slot.
 * @param trailing a trailing control slot.
 * @param subtitleColor overrides the subtitle's colour. For a row whose second line reports a *state*
 *   rather than a value — a download that failed, a source that couldn't install — where the muted
 *   default reads as "here is a detail" when it needs to read as "here is a problem". Colour is never the
 *   whole signal: such a row is expected to carry the same meaning in its [leading] glyph and its words.
 * @param destructive tints the [title] `danger`. Same name and same meaning as [TanukiMenuItem]'s, so a
 *   "delete everything" reads identically whether it is a menu item or a row. As there, colour is never
 *   the whole signal: such a row is expected to sit apart from the others, carry a matching [leading]
 *   glyph, and confirm.
 */
@Composable
fun TanukiListRow(
    title: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    subtitleColor: Color? = null,
    destructive: Boolean = false
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TanukiTheme.spacing.md),
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = ListRowMinHeight)
                .then(
                    if (onClick != null) {
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onClick
                        )
                    } else {
                        Modifier
                    }
                ).padding(horizontal = TanukiTheme.spacing.lg, vertical = TanukiTheme.spacing.sm)
    ) {
        if (leading != null) {
            leading()
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = TanukiTheme.typography.body,
                color = if (destructive) TanukiTheme.colors.danger else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = TanukiTheme.typography.caption,
                    color = subtitleColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        trailing?.invoke(this)
    }
}

@Composable
@Preview
private fun TanukiListRowPreview() {
    TanukiTheme {
        TanukiListRow(
            title = "Appearance",
            subtitle = "System",
            leading = { ContrastIcon(tint = TanukiTheme.colors.accent) },
            onClick = {}
        )
    }
}