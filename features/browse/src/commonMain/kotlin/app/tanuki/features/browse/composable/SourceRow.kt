package app.tanuki.features.browse.composable

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import app.tanuki.assets.designSystem.TanukiTheme
import app.tanuki.assets.designSystem.component.SourceAvatar
import app.tanuki.assets.designSystem.component.TanukiCard

/**
 * One row in the source list: the source's badge, its name, and a language/version subtitle. Tapping
 * anywhere on the row opens that source's browse screen.
 *
 * @param name source display name.
 * @param subtitle resolved secondary line (language, and version when the source reports one).
 * @param onClick open this source.
 */
@Composable
internal fun SourceRow(
    name: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = TanukiTheme.spacing
    TanukiCard(modifier = modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // No per-source brand colour yet — use the one accent, matching the design's restraint.
            SourceAvatar(name = name, color = TanukiTheme.colors.accent)
            Column(modifier = Modifier.weight(1f).padding(start = spacing.md)) {
                Text(
                    text = name,
                    style = TanukiTheme.typography.body,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = TanukiTheme.typography.caption,
                    color = TanukiTheme.colors.textFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
