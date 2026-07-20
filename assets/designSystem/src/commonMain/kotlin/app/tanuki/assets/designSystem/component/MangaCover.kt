package app.tanuki.assets.designSystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tanuki.assets.designSystem.TanukiRadii
import app.tanuki.assets.designSystem.TanukiTheme
import app.tanuki.assets.designSystem.coverGradient

private const val IMAGE_BOX_SIZE = 10

/**
 * The single manga-cover primitive for the whole app — the Library grid, the Activity leaderboard, and
 * the MangaDetail hero all render this, so cover framing lives in exactly one place (ported from the iOS
 * `MangaCoverView`).
 *
 * Fixed at a 2:3 aspect ratio; the caller sizes it via [modifier] (`Modifier.width(...)` — height
 * follows). Rounded corners and a hairline stroke frame the art. Optional unread / downloaded badges
 * overlay it for library/browse contexts.
 *
 * **Image-loader-agnostic by design.** The design system does not depend on an image library: pass the
 * decoded art via [image] (the feature layer supplies e.g., a Coil `AsyncImage` routed through the shared
 * Cloudflare-aware cover pipeline). When [image] is `null` — no cover, loading, or failure — the
 * deterministic [coverGradient] from [seed] shows instead. The title caption *below* a grid cover is tile
 * chrome, not part of the cover; it belongs to the library card that composes this.
 *
 * @param seed stable key (the manga id) that picks the fallback gradient, so a series keeps one colour.
 * @param image optional art slot filling the cover; `null` renders the gradient fallback.
 */
@Composable
fun MangaCover(
    seed: String,
    modifier: Modifier = Modifier,
    unread: UInt = 0u,
    downloaded: Boolean = false,
    cornerRadius: Dp = TanukiRadii.cover,
    image: (@Composable BoxScope.() -> Unit)? = null
) {
    val shape = RoundedCornerShape(cornerRadius)
    val colors = TanukiTheme.colors
    Box(
        modifier =
            modifier
                .aspectRatio(COVER_ASPECT)
                .clip(shape)
                .background(coverGradient(seed))
                .border(0.5.dp, colors.scrimSoft, shape)
    ) {
        image?.invoke(this)

        if (downloaded) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(TanukiTheme.spacing.lg)
                    .size(IMAGE_BOX_SIZE.dp)
                    .clip(CircleShape)
                    .background(colors.success)
            )
        }

        if (unread > 0u) {
            UnreadBadge(
                count = unread,
                modifier = Modifier.align(Alignment.TopEnd).padding(TanukiTheme.spacing.lg)
            )
        }
    }
}

/** Cover art aspect ratio (width: height). */
private const val COVER_ASPECT = 2f / 3f

@Composable
private fun UnreadBadge(
    count: UInt,
    modifier: Modifier = Modifier
) {
    val colors = TanukiTheme.colors
    Text(
        text = count.toString(),
        style = TanukiTheme.typography.label,
        color = MaterialTheme.colorScheme.onPrimary,
        modifier =
            modifier
                .clip(TanukiRadii.pillShape)
                .background(colors.unread)
                .padding(horizontal = 6.dp, vertical = 3.dp)
    )
}

@Composable
@Preview
private fun MangaCoverPreview() {
    MangaCover(seed = "123", unread = 1u)
}

@Composable
@Preview
private fun MangaCoverPreviewDownloaded() {
    MangaCover(seed = "1234", unread = 1u, downloaded = true)
}

@Composable
@Preview
private fun MangaCoverPreviewNoImage() {
    MangaCover(seed = "12345")
}