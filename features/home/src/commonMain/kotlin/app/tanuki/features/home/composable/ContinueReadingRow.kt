package app.tanuki.features.home.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tanuki.assets.designSystem.TanukiTheme
import app.tanuki.assets.designSystem.component.MangaCoverTile
import app.tanuki.features.home.resources.Res
import app.tanuki.features.home.resources.home_continue_reading_chapter
import app.tanuki.features.home.viewmodel.ContinueReadingItem
import org.jetbrains.compose.resources.stringResource

/** Card width; wide enough to read the overlaid title, narrow enough that the next card peeks in. */
private val CARD_WIDTH = 150.dp

/**
 * The For You "Continue Reading" carousel: one card per in-progress library series, most-recently-read
 * first, scrolled horizontally.
 *
 * Each card reuses the shared [MangaCoverTile] (cover art + title over a scrim) that the Library and the
 * source grid render, so the three surfaces feel like one app, with the resumable chapter captioned
 * underneath. There is deliberately **no progress bar**: the domain records the last page read but not a
 * chapter's page count, so a fill fraction would be invented rather than measured — it can land with the
 * reader.
 *
 * @param items the cards to show.
 * @param onItemClick a card was tapped — resume that chapter.
 */
@Composable
internal fun ContinueReadingRow(
    items: List<ContinueReadingItem>,
    onItemClick: (ContinueReadingItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = TanukiTheme.spacing
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = spacing.gutter),
        horizontalArrangement = Arrangement.spacedBy(spacing.gridGap)
    ) {
        items(items, key = { it.mangaId.toString() }) { item ->
            Column(modifier = Modifier.width(CARD_WIDTH)) {
                MangaCoverTile(
                    seed = item.mangaId.toString(),
                    title = item.title,
                    coverUrl = item.coverUrl,
                    onClick = { onItemClick(item) }
                )
                Text(
                    text =
                        item.chapterNumberLabel
                            ?.let { stringResource(Res.string.home_continue_reading_chapter, it) }
                            ?: item.chapterName,
                    style = TanukiTheme.typography.caption,
                    color = TanukiTheme.colors.textFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = spacing.sm)
                )
            }
        }
    }
}
