package app.tanuki.assets.designSystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.tanuki.assets.designSystem.TanukiTheme

/**
 * The design system's empty-state block: a faint illustrative glyph stacked over an [message] caption,
 * centered as a column. Used when a screen has nothing to show — an empty library, a search that matches
 * nothing, no downloads — so the surface reads as intentional rather than blank.
 *
 * [message] is UI copy, so the caller passes an **already-resolved** string (`stringResource(...)`); the
 * component never inlines display literals. The default [icon] is the open-book glyph; pass another
 * design-system icon (e.g. a search glyph) to fit a different context.
 */
@Composable
fun TanukiEmptyState(
    message: String,
    modifier: Modifier = Modifier,
    icon: @Composable (tint: Color) -> Unit = { tint -> EmptyLibraryIcon(tint = tint) }
) {
    Column(
        modifier = modifier.padding(horizontal = TanukiTheme.spacing.gutter),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(TanukiTheme.spacing.md)
    ) {
        icon(TanukiTheme.colors.textFaint)
        Text(
            text = message,
            style = TanukiTheme.typography.body,
            color = TanukiTheme.colors.textFaint,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
@Preview
private fun TanukiEmptyStatePreview() {
    TanukiTheme {
        TanukiEmptyState(message = "No titles match “berserk”")
    }
}