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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tanuki.assets.designSystem.TanukiTheme
import app.tanuki.assets.designSystem.component.MangaCoverTile
import app.tanuki.features.activity.resources.Res
import app.tanuki.features.activity.resources.activity_chapter_range
import app.tanuki.features.activity.resources.activity_chapter_single
import app.tanuki.features.activity.resources.activity_chapters
import app.tanuki.features.activity.resources.activity_cover_content_description
import app.tanuki.features.activity.viewmodel.HistoryRow
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

private val COVER_WIDTH = 40.dp

/**
 * One history row — every chapter of a series read that day: the cover, the title over
 * "4 chapters · Ch. 418–421", and the time of the latest read. Tapping it continues reading.
 */
@Composable
internal fun HistoryRowItem(
    row: HistoryRow,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = TanukiTheme.spacing.gutter, vertical = TanukiTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(TanukiTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val coverDescription = stringResource(Res.string.activity_cover_content_description, row.title)
        MangaCoverTile(
            seed = row.mangaId.toString(),
            title = "",
            coverUrl = row.coverUrl,
            coverHeaders = row.coverHeaders,
            onClick = onClick,
            modifier = Modifier.width(COVER_WIDTH).semantics { contentDescription = coverDescription }
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TanukiTheme.spacing.xxs)) {
            Text(
                text = row.title,
                style = TanukiTheme.typography.headline,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = historyMeta(row),
                style = TanukiTheme.typography.body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = "${row.hour.toString().padStart(2, '0')}:${row.minute.toString().padStart(2, '0')}",
            style = TanukiTheme.typography.caption,
            color = TanukiTheme.colors.textFaint
        )
    }
}

/** "4 chapters · Ch. 418–421", "1 chapter · Ch. 112", or just the count when the chapters have no numbers. */
@Composable
private fun historyMeta(row: HistoryRow): String {
    val count = pluralStringResource(Res.plurals.activity_chapters, row.chapterCount, row.chapterCount)
    val first = row.firstChapter ?: return count
    val last = row.lastChapter ?: first
    val range =
        if (first == last) {
            stringResource(Res.string.activity_chapter_single, first)
        } else {
            stringResource(Res.string.activity_chapter_range, first, last)
        }
    return "$count · $range"
}
