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
import app.tanuki.assets.designSystem.TanukiSpacing
import app.tanuki.assets.designSystem.TanukiTheme
import app.tanuki.assets.designSystem.component.MangaCoverTile
import app.tanuki.features.home.resources.Res
import app.tanuki.features.home.resources.home_chapter_range
import app.tanuki.features.home.resources.home_continue_reading_chapter
import app.tanuki.features.home.resources.home_new_chapters_count
import app.tanuki.features.home.resources.home_cover_content_description
import app.tanuki.features.home.resources.home_time_days
import app.tanuki.features.home.resources.home_time_hours
import app.tanuki.features.home.resources.home_time_just_now
import app.tanuki.features.home.resources.home_time_minutes
import app.tanuki.features.home.resources.home_time_weeks
import app.tanuki.features.home.viewmodel.NewChapterItem
import app.tanuki.core.presentation.Elapsed
import app.tanuki.core.presentation.ElapsedUnit
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

private val COVER_WIDTH = 40.dp

/**
 * Where a [NewChapterRow]'s title starts: the row's leading padding (`gutter`) + the cover + the gap
 * before the text (`md`). Insetting the divider between two rows by it runs the rule under the titles
 * rather than the covers — the grouped-list convention the source and settings lists also follow.
 */
internal val NewChapterRowDividerInset = TanukiSpacing.gutter + COVER_WIDTH + TanukiSpacing.md

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
            // One chapter: "Chapter 184", or its title when the source only titles them (untitled *and*
            // unnumbered leaves the line off). A batch: "3 chapters · Ch. 418–421" (or just the count).
            val first = item.firstChapter
            val last = item.lastChapter
            val subtitle =
                if (item.chapterCount > 1) {
                    val count = pluralStringResource(Res.plurals.home_new_chapters_count, item.chapterCount, item.chapterCount)
                    if (first != null && last != null && first != last) {
                        count + " · " + stringResource(Res.string.home_chapter_range, first, last)
                    } else {
                        count
                    }
                } else {
                    first?.let { stringResource(Res.string.home_continue_reading_chapter, it) }
                        ?: item.chapterName.takeIf { it.isNotBlank() }
                }
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
            text = relativeCaption(item.discoveredAgo),
            style = TanukiTheme.typography.caption,
            color = TanukiTheme.colors.textFaint
        )
    }
}

/** The localized caption for how long ago an update was found ("Just now", "5 min ago", …). */
@Composable
private fun relativeCaption(elapsed: Elapsed): String {
    val count = elapsed.count
    return when (elapsed.unit) {
        ElapsedUnit.JustNow -> stringResource(Res.string.home_time_just_now)
        ElapsedUnit.Minutes -> pluralStringResource(Res.plurals.home_time_minutes, count, count)
        ElapsedUnit.Hours -> pluralStringResource(Res.plurals.home_time_hours, count, count)
        ElapsedUnit.Days -> pluralStringResource(Res.plurals.home_time_days, count, count)
        ElapsedUnit.Weeks -> pluralStringResource(Res.plurals.home_time_weeks, count, count)
    }
}
