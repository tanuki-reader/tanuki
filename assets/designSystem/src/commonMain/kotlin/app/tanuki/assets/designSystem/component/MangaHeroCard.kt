package app.tanuki.assets.designSystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tanuki.assets.designSystem.TanukiRadii
import app.tanuki.assets.designSystem.TanukiTheme
import app.tanuki.assets.designSystem.coverGround
import coil3.compose.AsyncImage

/** Card height; the card's width comes from [MangaHeroCard]'s caller. */
private val HeroCardHeight = 168.dp

/** Width of the elevated portrait cover; its height follows the 2:3 ratio and fits within the padding. */
private val HeroCardCoverWidth = 92.dp

/** Corner radius of the card panel — the sheet radius (16.dp), softer than a cover's. */
private val HeroCardCorner = TanukiRadii.card

/**
 * A wide "hero" card: the title on the left of an opaque `surface` panel, the portrait [MangaCover] at the
 * trailing edge. The landscape counterpart to the portrait [MangaCoverTile]. No backdrop wash: Android has
 * no blur, and the cover carries the colour.
 *
 * Loads its own art from [coverUrl] via Coil's singleton `ImageLoader` (configured in `App.kt`), exactly
 * like [MangaCoverTile], so callers stay image-loader-agnostic; a `null` [coverUrl] leaves the cover on its
 * fallback [coverGround].
 *
 * The caller fixes the card [width] (height is fixed by the design so a row of cards aligns); in a
 * carousel, size it so the next card peeks in.
 *
 * @param seed stable key (the manga id) picking the fallback ground, so a series keeps one colour.
 * @param title the series title, drawn prominently on the left.
 * @param coverUrl remote cover art URL; `null` shows the fallback ground.
 * @param onClick invoked when the card is tapped.
 * @param width the card's width; height is fixed at [HeroCardHeight].
 * @param coverHeaders headers the cover fetch must carry — empty for almost every source, non-empty
 *   when its image CDN checks the `Referer` (see [coverImageRequest]).
 * @param subtitle an optional faint line under the title (e.g. the resumable chapter).
 */
@Composable
fun MangaHeroCard(
    seed: String,
    title: String,
    coverUrl: String?,
    onClick: () -> Unit,
    width: Dp,
    modifier: Modifier = Modifier,
    coverHeaders: Map<String, String> = emptyMap(),
    subtitle: String? = null
) {
    val spacing = TanukiTheme.spacing
    val colors = TanukiTheme.colors
    val shape = RoundedCornerShape(HeroCardCorner)

    Box(
        modifier =
            modifier
                .width(width)
                .height(HeroCardHeight)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surface)
                .border(0.5.dp, colors.line, shape)
                .clickable(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.lg),
            modifier = Modifier.matchParentSize().padding(spacing.lg)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = TanukiTheme.typography.titleL,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                subtitle?.let {
                    Spacer(Modifier.height(spacing.xs))
                    Text(
                        text = it,
                        style = TanukiTheme.typography.caption.copy(fontWeight = FontWeight.Medium),
                        color = colors.textFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            MangaCover(
                seed = seed,
                modifier = Modifier.width(HeroCardCoverWidth),
                image = coverUrl?.let { url ->
                    {
                        AsyncImage(
                            model = coverImageRequest(url, coverHeaders),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.matchParentSize()
                        )
                    }
                }
            )
        }
    }
}

@Composable
@Preview
private fun MangaHeroCardPreview() {
    MangaHeroCard(
        seed = "123",
        title = "Moby Dick",
        coverUrl = null,
        onClick = {},
        width = 300.dp,
        subtitle = "Chapter 12"
    )
}