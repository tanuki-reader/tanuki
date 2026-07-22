package app.tanuki.assets.designSystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.tanuki.assets.designSystem.TanukiRadii
import app.tanuki.assets.designSystem.TanukiTheme
import coil3.compose.AsyncImage

/**
 * A source's badge in the source list: its remote icon/logo when it reports one ([iconUrl]), otherwise
 * the lettered [SourceAvatar]. The avatar is always drawn as the base and the icon layers on top, so the
 * avatar also shows through while the image loads or if it fails — the same fallback-through trick
 * [MangaCoverTile] uses for cover art. Icon art comes from Coil's singleton `ImageLoader` (configured in
 * `App.kt`), so callers don't wire up image loading.
 *
 * @param name source display name; its initial seeds the fallback avatar.
 * @param iconUrl remote icon URL, or `null` to show only the lettered avatar.
 * @param color the fallback avatar's tint.
 */
@Composable
fun SourceIcon(
    name: String,
    iconUrl: String?,
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.size(ICON_SIZE).clip(TanukiRadii.chipShape)) {
        SourceAvatar(name = name, color = color, modifier = Modifier.matchParentSize())
        if (iconUrl != null) {
            AsyncImage(
                model = iconUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize()
            )
        }
    }
}

/** Matches [SourceAvatar]'s size so the fallback fills the frame exactly. */
private val ICON_SIZE = 34.dp

@Composable
@Preview
private fun SourceIconFallbackPreview() {
    TanukiTheme {
        SourceIcon(name = "MangaDex", iconUrl = null, color = TanukiTheme.colors.accent)
    }
}
