package app.tanuki.assets.designSystem.component

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.tanuki.assets.designSystem.TanukiRadii
import app.tanuki.assets.designSystem.TanukiTheme

private const val TAG_BORDER_WIDTH = 1

/**
 * A small outlined pill for a genre / metadata tag (ported from the iOS `TagView`). [text] is content
 * data (a genre name), so it is rendered verbatim — not a localizable UI string.
 */
@Composable
fun TanukiTag(
    text: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        style = TanukiTheme.typography.caption,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
            modifier
                .border(TAG_BORDER_WIDTH.dp, MaterialTheme.colorScheme.outline, TanukiRadii.pillShape)
                .padding(horizontal = TanukiTheme.spacing.sm, vertical = TanukiTheme.spacing.xs)
    )
}

@Composable
@Preview
private fun TagPreview() {
    TanukiTag(text = "Action")
}