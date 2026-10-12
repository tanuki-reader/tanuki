package app.tanuki.assets.designSystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.tanuki.assets.designSystem.TanukiRadii
import app.tanuki.assets.designSystem.TanukiTheme

/**
 * Genre / category chip in its selectable form (ported from the iOS `FilterChipView`, pre-Liquid-Glass
 * dialect) — outline when off, orange-tint fill when on, with an optional muted result count. [dimmed]
 * greys out a chip that would yield zero results under the current selection.
 *
 * [text] is content data (a genre name) → rendered verbatim.
 */
@Composable
fun TanukiFilterChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    count: Int? = null,
    dimmed: Boolean = false
) {
    val accent = TanukiTheme.colors.accentHover
    val textColor = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant
    val countColor = if (selected) accent else TanukiTheme.colors.textFaint
    val fill = if (selected) TanukiTheme.colors.accentTint else Color.Transparent

    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .alpha(if (dimmed) 0.4f else 1f)
                .clip(TanukiRadii.chipShape)
                .background(fill)
                .then(
                    if (selected) {
                        Modifier
                    } else {
                        Modifier.border(1.dp, MaterialTheme.colorScheme.outline, TanukiRadii.chipShape)
                    }
                ).clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick
                ).defaultMinSize(minHeight = 30.dp)
                .padding(horizontal = TanukiTheme.spacing.md)
    ) {
        Text(text = text, style = TanukiTheme.typography.caption, color = textColor)
        if (count != null) {
            Text(text = count.toString(), style = TanukiTheme.typography.caption, color = countColor)
        }
    }
}

@Composable
@Preview
private fun TanukiFilterChipPreview() {
    TanukiFilterChip(
        text = "Action",
        selected = true,
        onClick = {}
    )
}

@Composable
@Preview
private fun TanukiFilterChipPreviewDimmed() {
    TanukiFilterChip(
        text = "Action",
        selected = false,
        onClick = {},
        dimmed = true
    )
}