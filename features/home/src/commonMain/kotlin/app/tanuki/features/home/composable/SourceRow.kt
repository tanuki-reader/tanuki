package app.tanuki.features.home.composable

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.tanuki.assets.designSystem.TanukiTheme
import app.tanuki.assets.designSystem.component.SourceIcon
import app.tanuki.assets.designSystem.component.TanukiListRow

/**
 * One row inside a language group: the source's icon and its name. Tapping anywhere opens that source's
 * browse leaf.
 *
 * It is the shared [TanukiListRow] — the same drill-down row the Settings index is built from, so the two
 * lists read as one thing. The row carries no surface of its own: [LanguageSourceGroup] wraps the whole
 * group in one, so a language's sources read as a single grouped list rather than a stack of separate
 * cards. No language subtitle either — the group header already states it.
 *
 * @param name source display name.
 * @param iconUrl the source's remote icon, or `null` for the lettered fallback.
 * @param onClick open this source.
 */
@Composable
internal fun SourceRow(
    name: String,
    iconUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TanukiListRow(
        title = name,
        modifier = modifier,
        onClick = onClick,
        leading = {
            // No per-source brand colour yet — the one accent tints the fallback, matching the design's
            // restraint.
            SourceIcon(name = name, iconUrl = iconUrl, color = TanukiTheme.colors.accent)
        }
    )
}
