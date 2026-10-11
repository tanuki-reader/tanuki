package app.tanuki.features.activity.composable.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import app.tanuki.assets.designSystem.component.MangaCoverTile
import app.tanuki.features.activity.resources.Res
import app.tanuki.features.activity.resources.activity_chapters
import app.tanuki.features.activity.resources.activity_rank_down
import app.tanuki.features.activity.resources.activity_rank_new
import app.tanuki.features.activity.resources.activity_rank_same
import app.tanuki.features.activity.resources.activity_rank_up
import app.tanuki.features.activity.viewmodel.RankChange
import app.tanuki.features.activity.viewmodel.RankedSeries
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

private val COVER_WIDTH = 36.dp
private val RANK_WIDTH = 24.dp

/**
 * One row of the "most read" leaderboard: rank, cover, title, chapters read in the period, and how the
 * rank moved since the previous period — in words ("Up 2", "New"), colour only reinforcing them.
 */
@Composable
internal fun MostReadRow(
    item: RankedSeries,
    onOpenSeries: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth().clickable(onClick = onOpenSeries).padding(vertical = TanukiTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(TanukiTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = item.rank.toString(),
            style = TanukiTheme.typography.headline,
            color = TanukiTheme.colors.textFaint,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(RANK_WIDTH)
        )
        MangaCoverTile(
            seed = item.mangaId.toString(),
            title = "",
            coverUrl = item.coverUrl,
            coverHeaders = item.coverHeaders,
            onClick = onOpenSeries,
            modifier = Modifier.width(COVER_WIDTH)
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TanukiTheme.spacing.xxs)) {
            Text(
                text = item.title,
                style = TanukiTheme.typography.headline,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = pluralStringResource(Res.plurals.activity_chapters, item.count, item.count),
                style = TanukiTheme.typography.caption,
                color = TanukiTheme.colors.textFaint
            )
        }
        val colors = TanukiTheme.colors
        val (text, color) =
            when (val change = item.change) {
                RankChange.New -> stringResource(Res.string.activity_rank_new) to colors.accent
                RankChange.Same -> stringResource(Res.string.activity_rank_same) to colors.textFaint
                is RankChange.Up -> stringResource(Res.string.activity_rank_up, change.places) to colors.success
                is RankChange.Down -> stringResource(Res.string.activity_rank_down, change.places) to colors.textFaint
            }
        Text(text = text, style = TanukiTheme.typography.caption, color = color)
    }
}
