package app.tanuki.features.home.composable

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
import app.tanuki.features.home.resources.Res
import app.tanuki.features.home.resources.home_continue_reading_chapter
import app.tanuki.features.home.resources.home_cover_content_description
import app.tanuki.features.home.resources.home_time_days
import app.tanuki.features.home.resources.home_time_hours
import app.tanuki.features.home.resources.home_time_just_now
import app.tanuki.features.home.resources.home_time_minutes
import app.tanuki.features.home.resources.home_time_weeks
import app.tanuki.features.home.viewmodel.NewChapterItem
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock
import kotlin.time.Instant

private val COVER_WIDTH = 40.dp

/**
 * One "New Chapters" row: the series' cover, its title over the chapter that arrived, and a relative
 * "found at" caption. Tapping it opens that chapter in the reader.
 *
 * Deliberately the same shape as the Activity screen's history row — both are "a chapter, in a series,
 * at a time", and Home's two For You sections read as one list when the rows match. The series title
 * and chapter name are **content** (from the source), so they stay plain strings; only the caption is
 * localized UI copy.
 *
 * @param item the update to render.
 * @param onClick the row was tapped — open its chapter.
 */
@Composable
internal fun NewChapterRow(
    item: NewChapterItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(
                    horizontal = TanukiTheme.spacing.gutter,
                    vertical = TanukiTheme.spacing.sm
                ),
        horizontalArrangement = Arrangement.spacedBy(TanukiTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val coverDescription = stringResource(Res.string.home_cover_content_description, item.title)
        MangaCoverTile(
            seed = item.mangaId.toString(),
            title = "",
            coverUrl = item.coverUrl,
            coverHeaders = item.coverHeaders,
            onClick = onClick,
            modifier =
                Modifier
                    .width(COVER_WIDTH)
                    .semantics { contentDescription = coverDescription }
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(TanukiTheme.spacing.xxs)
        ) {
            Text(
                text = item.title,
                style = TanukiTheme.typography.headline,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // A source that numbers its chapters gets "Chapter 184"; one that only titles them shows the
            // title. Untitled *and* unnumbered leaves the line off rather than inventing a label.
            val subtitle =
                item.chapterNumberLabel
                    ?.let { stringResource(Res.string.home_continue_reading_chapter, it) }
                    ?: item.chapterName.takeIf { it.isNotBlank() }
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = TanukiTheme.typography.body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Text(
            text = relativeDiscoveredAt(item.discoveredAt),
            style = TanukiTheme.typography.caption,
            color = TanukiTheme.colors.textFaint
        )
    }
}

/**
 * A localized, relative caption for when an update was found — "Just now", "5 min ago", "3 h ago",
 * "2 days ago", "4 weeks ago". Coarse buckets, so no locale date formatting is needed; weeks is the
 * widest, which is plenty for something the user is expected to read soon.
 */
@Composable
private fun relativeDiscoveredAt(
    discoveredAt: Instant,
    now: Instant = Clock.System.now()
): String {
    val elapsed = now - discoveredAt
    val minutes = elapsed.inWholeMinutes
    val hours = elapsed.inWholeHours
    val days = elapsed.inWholeDays
    return when {
        minutes < 1 -> stringResource(Res.string.home_time_just_now)
        minutes < 60 -> pluralStringResource(Res.plurals.home_time_minutes, minutes.toInt(), minutes.toInt())
        hours < 24 -> pluralStringResource(Res.plurals.home_time_hours, hours.toInt(), hours.toInt())
        days < 7 -> pluralStringResource(Res.plurals.home_time_days, days.toInt(), days.toInt())
        else -> {
            val weeks = (days / 7).toInt()
            pluralStringResource(Res.plurals.home_time_weeks, weeks, weeks)
        }
    }
}