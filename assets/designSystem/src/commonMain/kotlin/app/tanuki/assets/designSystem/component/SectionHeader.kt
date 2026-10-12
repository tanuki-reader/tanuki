package app.tanuki.assets.designSystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import app.tanuki.assets.designSystem.TanukiTheme

/**
 * A section title with an optional trailing caption (a count), or an optional [action] ("See all",
 * "Update all") — the design system's `SectionHeader`, ported from the iOS `SectionHeaderView`.
 *
 * Both labels are UI copy, so callers pass **already-resolved localized strings** (`stringResource(...)`)
 * — the Compose equivalent of the SwiftUI `LocalizedStringKey` parameters. The component never inlines
 * literals.
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    action: String? = null,
    onAction: () -> Unit = {}
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = TanukiTheme.typography.headline,
            color = MaterialTheme.colorScheme.onSurface
        )
        when {
            action != null -> TanukiButton(text = action, onClick = onAction, variant = TanukiButtonVariant.Text, size = TanukiButtonSize.Small)
            trailing != null ->
                Text(
                    text = trailing,
                    style = TanukiTheme.typography.caption,
                    color = TanukiTheme.colors.textFaint
                )
        }
    }
}

@Composable
@Preview
private fun SectionHeaderPreview() {
    SectionHeader(title = "Section title", trailing = "123")
}