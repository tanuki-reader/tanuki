package app.tanuki.assets.designSystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.tanuki.assets.designSystem.TanukiTheme

/**
 * A star glyph followed by a numeric rating (ported from the iOS `RatingView`). [value] is content
 * data (e.g. "4.7"), rendered verbatim. The star uses the warning (amber) token to match the design.
 */
@Composable
fun RatingLabel(
    value: String,
    modifier: Modifier = Modifier
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        Text(text = "★", style = TanukiTheme.typography.caption, color = TanukiTheme.colors.warning)
        Text(text = value, style = TanukiTheme.typography.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
@Preview
private fun RatingLabelPreview() {
    RatingLabel(value = "4.7")
}