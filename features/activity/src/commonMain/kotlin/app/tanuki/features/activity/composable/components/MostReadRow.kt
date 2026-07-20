package app.tanuki.features.activity.composable.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tanuki.assets.designSystem.TanukiTheme
import app.tanuki.assets.designSystem.component.ArrowDownwardIcon
import app.tanuki.assets.designSystem.component.ArrowUpwardIcon
import app.tanuki.assets.designSystem.component.MangaCover
import app.tanuki.core.domain.history.model.MostReadManga
import app.tanuki.core.domain.history.model.MostReadManga.ReadTrend

private val COVER_WIDTH = 36.dp
private val RANK_WIDTH = 24.dp

/**
 * One row of the "most read" leaderboard: its [rank], the series cover and title, how many chapters were
 * read in the period ([chaptersLabel]), and a trend arrow versus the previous period — up (success),
 * down (danger), or flat (a faint dash).
 *
 * [chaptersLabel] is resolved UI copy passed by the caller; the manga title is content.
 */
@Composable
internal fun MostReadRow(
    rank: Int,
    item: MostReadManga,
    chaptersLabel: String,
    onOpenSeries: (MostReadManga) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(vertical = TanukiTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(TanukiTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = rank.toString(),
            style = TanukiTheme.typography.headline,
            color = TanukiTheme.colors.textFaint,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(RANK_WIDTH)
        )
        MangaCover(
            seed = item.manga.id.toString(),
            modifier = Modifier.width(COVER_WIDTH).clickable { onOpenSeries(item) }
        )
        Column(
            modifier = Modifier.weight(1f).clickable { onOpenSeries(item) },
            verticalArrangement = Arrangement.spacedBy(TanukiTheme.spacing.xxs)
        ) {
            Text(
                text = item.manga.title.value,
                style = TanukiTheme.typography.headline,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = chaptersLabel,
                style = TanukiTheme.typography.caption,
                color = TanukiTheme.colors.textFaint
            )
        }
        TrendIndicator(item.trend)
    }
}

/** The period-over-period trend glyph: up arrow (success), down arrow (danger), or a flat dash (faint). */
@Composable
private fun TrendIndicator(trend: ReadTrend) {
    val colors = TanukiTheme.colors
    when (trend) {
        ReadTrend.UP -> {
            ArrowUpwardIcon(tint = colors.success)
        }

        ReadTrend.DOWN -> {
            ArrowDownwardIcon(tint = colors.danger)
        }

        ReadTrend.FLAT -> {
            Box(Modifier.width(18.dp)) {
                Text(
                    text = "—",
                    style = TanukiTheme.typography.body,
                    color = colors.textFaint,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}