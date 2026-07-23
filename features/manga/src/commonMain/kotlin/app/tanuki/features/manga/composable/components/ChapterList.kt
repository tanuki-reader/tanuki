package app.tanuki.features.manga.composable.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.modifier.modifierLocalConsumer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tanuki.assets.designSystem.TanukiTheme
import app.tanuki.assets.designSystem.component.ArrowDownwardIcon
import app.tanuki.assets.designSystem.component.ArrowUpwardIcon
import app.tanuki.assets.designSystem.component.TanukiIconButton
import app.tanuki.core.domain.chapter.model.Chapter
import app.tanuki.core.domain.chapter.model.ChapterNumber
import app.tanuki.features.manga.resources.Res
import app.tanuki.features.manga.resources.manga_chapter_number
import app.tanuki.features.manga.resources.manga_chapter_untitled
import app.tanuki.features.manga.resources.manga_chapters_sort
import app.tanuki.features.manga.resources.manga_chapters_total
import app.tanuki.features.manga.resources.manga_no_chapters
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Diameter of the leading unread indicator dot. */
private val UnreadDotSize = 8.dp

/** Direction-arrow size in the chapter sort toggle. */
private val SortArrowSize = 18.dp

/**
 * Adds the chapters section — a header (title, count, and a sort-by-number toggle) followed by one row
 * per chapter — into a parent [LazyListScope]. Kept lazy (not a nested scroll) so a series with hundreds
 * of chapters only composes the rows on screen. Read chapters are dimmed; unread ones carry a leading
 * accent dot; tapping a row opens the reader at that chapter.
 *
 * @param chapters the chapters to list, already ordered by the ViewModel.
 * @param ascending the current sort direction, shown by the toggle's arrow.
 * @param onToggleSort the sort toggle was tapped — flip ascending/descending.
 * @param onChapterClick a chapter row was tapped — open it in the reader.
 */
internal fun LazyListScope.chapterList(
    chapters: List<Chapter>,
    ascending: Boolean,
    onToggleSort: () -> Unit,
    onChapterClick: (Chapter) -> Unit
) {
    item(key = "chapters-header") {
        ChapterSectionHeader(count = chapters.size, ascending = ascending, onToggleSort = onToggleSort)
    }
    if (chapters.isEmpty()) {
        item(key = "chapters-empty") { EmptyChapters() }
    } else {
        items(chapters, key = { it.id.toString() }) { chapter ->
            ChapterRow(
                modifier = Modifier.animateItem(),
                chapter = chapter
            ) { onChapterClick(chapter) }
        }
    }
}

// Under the "Chapters" tab, the section needs no title of its own — just the total and the sort toggle.
@Composable
private fun ChapterSectionHeader(
    count: Int,
    ascending: Boolean,
    onToggleSort: () -> Unit
) {
    val spacing = TanukiTheme.spacing
    val sortDescription = stringResource(Res.string.manga_chapters_sort)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = spacing.gutter, end = spacing.sm)
                .padding(top = spacing.sm, bottom = spacing.xs)
    ) {
        Text(
            text = pluralStringResource(Res.plurals.manga_chapters_total, count, count),
            style = TanukiTheme.typography.caption,
            color = TanukiTheme.colors.textFaint,
            modifier = Modifier.weight(1f)
        )
        TanukiIconButton(
            onClick = onToggleSort,
            modifier = Modifier.semantics { contentDescription = sortDescription }
        ) {
            val tint = TanukiTheme.colors.accent
            if (ascending) {
                ArrowUpwardIcon(tint = tint, size = SortArrowSize)
            } else {
                ArrowDownwardIcon(tint = tint, size = SortArrowSize)
            }
        }
    }
}

@Composable
private fun EmptyChapters() {
    val spacing = TanukiTheme.spacing
    Text(
        text = stringResource(Res.string.manga_no_chapters),
        style = TanukiTheme.typography.body,
        color = TanukiTheme.colors.textFaint,
        modifier = Modifier.padding(horizontal = spacing.gutter, vertical = spacing.sm)
    )
}

@Composable
private fun ChapterRow(
    modifier: Modifier = Modifier,
    chapter: Chapter,
    onClick: () -> Unit
) {
    val spacing = TanukiTheme.spacing
    val read = chapter.progress?.alreadyRead == true
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = spacing.gutter, vertical = spacing.md)
                .alpha(if (read) 0.5f else 1f)
    ) {
        Box(
            Modifier
                .size(UnreadDotSize)
                .clip(CircleShape)
                .background(if (read) Color.Transparent else TanukiTheme.colors.unread)
        )
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = chapter.title(),
                style = TanukiTheme.typography.body,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            chapter.subtitle()?.let { subtitle ->
                Text(
                    text = subtitle,
                    style = TanukiTheme.typography.caption,
                    color = TanukiTheme.colors.textFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Row title: "Chapter 4" when the number is known, otherwise the source's chapter name — and when the
 * source gave neither (an unnumbered, untitled extra), a localized placeholder, so the row is never
 * blank. The number wins over the name because it's the stable, sortable thing.
 */
@Composable
private fun Chapter.title(): String =
    chapterNumber?.let { stringResource(Res.string.manga_chapter_number, it.display()) }
        ?: name?.value
        ?: stringResource(Res.string.manga_chapter_untitled)

/**
 * Row subtitle: the chapter name, shown only when the title already carries the number (else it would
 * repeat it). `null` for the many chapters sources publish with no title of their own.
 */
private fun Chapter.subtitle(): String? = chapterNumber?.let { name?.value }

/** "4" for whole numbers, "4.5" otherwise — drops the trailing ".0" a raw [Double] would show. */
internal fun ChapterNumber.display(): String = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()