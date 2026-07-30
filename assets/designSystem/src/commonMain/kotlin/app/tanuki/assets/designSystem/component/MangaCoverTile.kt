package app.tanuki.assets.designSystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import app.tanuki.assets.designSystem.TanukiRadii
import app.tanuki.assets.designSystem.TanukiTheme
import coil3.compose.AsyncImage

/**
 * A tappable cover tile: a [MangaCover] with the series [title] overlaid on a bottom scrim. This is the
 * one grid cell the Library and the source Browse grids both render, so the two feel like one app —
 * previously each feature carried its own near-identical copy.
 *
 * Loads its own art from [coverUrl] via Coil's singleton `ImageLoader` (configured in `App.kt`), so
 * every tile shows a real cover without each caller wiring up image loading; a `null` [coverUrl] leaves
 * the deterministic gradient fallback showing. The lower-level [MangaCover] primitive stays
 * loader-agnostic (its `image` slot) — this tile is the app-level convenience that fills it. Library
 * tiles also surface [downloaded] / [unread] badges; Browse tiles leave them at their defaults.
 *
 * @param seed stable key that picks the fallback gradient and keys the cover (the manga id in the
 *   library, the source-native key while browsing).
 * @param title the series title drawn over the scrim.
 * @param coverUrl remote cover art URL; `null` shows the gradient fallback.
 * @param onClick invoked when the tile is tapped.
 * @param coverHeaders headers the cover fetch must carry — empty for almost every source, non-empty
 *   when its image CDN checks the `Referer` (see [coverImageRequest]).
 * @param unread unread-chapter badge count; `0u` hides it.
 * @param downloaded whether to show the downloaded dot.
 */
@Composable
fun MangaCoverTile(
    seed: String,
    title: String,
    coverUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    coverHeaders: Map<String, String> = emptyMap(),
    unread: UInt = 0u,
    downloaded: Boolean = false
) {
    val spacing = TanukiTheme.spacing
    Box(
        modifier = modifier.clickable(onClick = onClick),
        contentAlignment = Alignment.BottomStart
    ) {
        MangaCover(
            seed = seed,
            modifier = Modifier.fillMaxWidth(),
            unread = unread,
            downloaded = downloaded,
            image = coverUrl?.let { url ->
                {
                    // Crop to fill the 2:3 frame — covers vary in aspect ratio, and the default Fit
                    // would letterbox them, showing the gradient through the gaps. While loading / on
                    // failure the gradient shows through instead.
                    AsyncImage(
                        model = coverImageRequest(url, coverHeaders),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize()
                    )
                }
            }
        )
        // Bottom protection gradient so the title stays legible over any cover art. Clipped to the
        // cover's rounded corners to sit flush inside the frame.
        Box(
            Modifier.matchParentSize().clip(TanukiRadii.coverShape).background(COVER_TITLE_SCRIM)
        )
        Text(
            text = title,
            style = TanukiTheme.typography.caption.copy(fontWeight = FontWeight.Bold),
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.sm, vertical = spacing.xs)
        )
    }
}

/** Transparent → dark wash over the lower cover, protecting the overlaid title from bright art. */
private val COVER_TITLE_SCRIM =
    Brush.verticalGradient(
        0.45f to Color.Transparent,
        1f to Color.Black.copy(alpha = 0.78f)
    )

@Composable
@Preview
private fun MangaCoverTilePreview() {
    MangaCoverTile(seed = "123", title = "A Long Manga Title That Wraps", coverUrl = null, onClick = {})
}

@Composable
@Preview
private fun MangaCoverTilePreviewBadges() {
    MangaCoverTile(
        seed = "456",
        title = "Downloaded",
        coverUrl = null,
        onClick = {},
        unread = 3u,
        downloaded = true
    )
}