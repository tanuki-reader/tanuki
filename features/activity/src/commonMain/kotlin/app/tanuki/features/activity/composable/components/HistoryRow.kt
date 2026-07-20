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
import app.tanuki.assets.designSystem.component.MangaCover
import app.tanuki.core.domain.history.model.HistoryEntry
import app.tanuki.features.activity.resources.Res
import app.tanuki.features.activity.resources.activity_cover_content_description
import app.tanuki.features.activity.resources.activity_time_days
import app.tanuki.features.activity.resources.activity_time_hours
import app.tanuki.features.activity.resources.activity_time_just_now
import app.tanuki.features.activity.resources.activity_time_minutes
import app.tanuki.features.activity.resources.activity_time_weeks
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock
import kotlin.time.Instant

private val COVER_WIDTH = 40.dp

/**
 * One reading-history row: the manga's cover, its title over the chapter that was read, and a relative
 * "read at" caption. Tapping it resumes reading (handled by the caller via [onClick]).
 *
 * The manga title and chapter name are **content** (from the source), so they're plain strings; only the
 * relative-time caption is localized UI copy, resolved here via [relativeReadAt].
 */
@Composable
internal fun HistoryRow(
    entry: HistoryEntry,
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
        val coverDescription =
            stringResource(Res.string.activity_cover_content_description, entry.manga.title.value)
        MangaCover(
            seed = entry.manga.id.toString(),
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
                text = entry.manga.title.value,
                style = TanukiTheme.typography.headline,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val chapterName = entry.chapter?.name?.value
            if (chapterName != null) {
                Text(
                    text = chapterName,
                    style = TanukiTheme.typography.body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Text(
            text = relativeReadAt(entry.readAt),
            style = TanukiTheme.typography.caption,
            color = TanukiTheme.colors.textFaint
        )
    }
}

/**
 * A localized, relative caption for when [readAt] happened — "Just now", "5 min ago", "3 h ago",
 * "2 days ago", "4 weeks ago". Buckets stay coarse (no absolute date) so no locale date formatting is
 * needed; weeks is the widest bucket, which is plenty for a reading-history glance.
 */
@Composable
private fun relativeReadAt(
    readAt: Instant,
    now: Instant = Clock.System.now()
): String {
    val elapsed = now - readAt
    val minutes = elapsed.inWholeMinutes
    val hours = elapsed.inWholeHours
    val days = elapsed.inWholeDays
    return when {
        minutes < 1 -> stringResource(Res.string.activity_time_just_now)
        minutes < 60 -> pluralStringResource(Res.plurals.activity_time_minutes, minutes.toInt(), minutes.toInt())
        hours < 24 -> pluralStringResource(Res.plurals.activity_time_hours, hours.toInt(), hours.toInt())
        days < 7 -> pluralStringResource(Res.plurals.activity_time_days, days.toInt(), days.toInt())
        else -> {
            val weeks = (days / 7).toInt()
            pluralStringResource(Res.plurals.activity_time_weeks, weeks, weeks)
        }
    }
}