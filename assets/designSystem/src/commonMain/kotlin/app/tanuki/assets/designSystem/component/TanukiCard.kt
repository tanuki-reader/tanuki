package app.tanuki.assets.designSystem.component

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tanuki.assets.designSystem.TanukiRadii
import app.tanuki.assets.designSystem.TanukiTheme

/**
 * The Material dialect's card/group surface — replaces the iOS `GlassCard`. A raised surface with a
 * hairline outline, corners from the token set. (Liquid Glass blur is iOS-only and intentionally not
 * reproduced here; on Android the brand is Material You, not glass.)
 */
@Composable
fun TanukiCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, TanukiRadii.chipShape),
        shape = TanukiRadii.chipShape,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp, // dark, flat surfaces — colour carries elevation, not tint
        content = content
    )
}

/**
 * A rounded card of rows with hairline dividers between them (ported from the iOS `GlassGroup`) — used
 * for grouped lists like Browse sources or a chapter list. The divider is inset from the leading edge, so
 * it aligns under row content rather than the card edge.
 *
 * @param row renders one item; the caller owns each row's internal padding.
 */
@Composable
fun <T> TanukiCardGroup(
    items: List<T>,
    modifier: Modifier = Modifier,
    dividerStartInset: Dp = TanukiTheme.spacing.lg,
    row: @Composable (T) -> Unit
) {
    TanukiCard(modifier = modifier) {
        Column {
            items.forEachIndexed { index, item ->
                row(item)
                if (index < items.lastIndex) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(start = dividerStartInset)
                    )
                }
            }
        }
    }
}

@Composable
@Preview
private fun TanukiCardPreview() {
    TanukiCard {
        Text("Hello")
    }
}

@Composable
@Preview
private fun TanukiCardGroupPreview() {
    TanukiCardGroup(items = listOf("Hello", "World")) { text ->
        Text(text)
    }
}