package app.tanuki.features.activity.composable.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import app.tanuki.assets.designSystem.TanukiTheme
import app.tanuki.assets.designSystem.component.TanukiCard

/**
 * A single headline metric — a large [value] over a caption [label], and an optional [detail] line —
 * inside a design-system card. Shared
 * by the all-time summary row and the per-period curve stats so every metric reads identically.
 */
@Composable
internal fun StatTile(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    detail: String? = null
) {
    TanukiCard(modifier = modifier) {
        Column(
            modifier = Modifier.padding(TanukiTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(TanukiTheme.spacing.xxs)
        ) {
            Text(
                text = value,
                style = TanukiTheme.typography.headline,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = label,
                style = TanukiTheme.typography.caption,
                color = TanukiTheme.colors.textFaint,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            detail?.let {
                Text(text = it, style = TanukiTheme.typography.caption, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}