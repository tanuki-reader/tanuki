package app.tanuki.assets.designSystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.tanuki.assets.designSystem.TanukiRadii
import app.tanuki.assets.designSystem.TanukiTheme

/**
 * A square, rounded source badge showing the source's initial over a tint colour (ported from the iOS
 * `SourceAvatar`). [name] is content data; only its first character is shown. [color] is the source's
 * brand tint, chosen by the caller.
 */
@Composable
fun SourceAvatar(
    name: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .size(AVATAR_SIZE)
                .clip(TanukiRadii.chipShape)
                .background(color)
    ) {
        Text(
            text = name.take(1).uppercase(),
            style = TanukiTheme.typography.headline,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onPrimary // onAccent
        )
    }
}

private val AVATAR_SIZE = 34.dp

@Composable
@Preview
private fun SourceAvatarPreview() {
    SourceAvatar(name = "MangaDex", color = Color.Red)
}