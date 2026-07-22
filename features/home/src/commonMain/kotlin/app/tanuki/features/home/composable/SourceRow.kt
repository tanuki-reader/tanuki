package app.tanuki.features.home.composable

import androidx.compose.foundation.clickable
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
import app.tanuki.assets.designSystem.component.SourceIcon

/**
 * One row inside a language group: the source's icon and its name. Tapping anywhere opens that source's
 * browse leaf.
 *
 * The row carries no card of its own — [LanguageSourceGroup] wraps the whole group in one, so the
 * language's sources read as a single grouped list (the design's one-card-per-section look) rather than
 * a stack of separate cards. No language subtitle either: the group header already states it.
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
    val spacing = TanukiTheme.spacing
    Row(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick).padding(spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // No per-source brand colour yet — the one accent tints the fallback, matching the design's restraint.
        SourceIcon(name = name, iconUrl = iconUrl, color = TanukiTheme.colors.accent)
        Text(
            text = name,
            style = TanukiTheme.typography.body,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = spacing.md)
        )
    }
}
