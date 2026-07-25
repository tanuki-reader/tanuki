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
import app.tanuki.assets.designSystem.component.FilterIcon
import app.tanuki.assets.designSystem.component.TanukiIconButton
import app.tanuki.core.domain.chapter.model.Chapter
import app.tanuki.core.domain.chapter.model.ChapterNumber
import app.tanuki.features.manga.resources.Res
import app.tanuki.features.manga.resources.manga_chapter_date
import app.tanuki.features.manga.resources.manga_chapter_meta
import app.tanuki.features.manga.resources.manga_chapter_number
import app.tanuki.features.manga.resources.manga_chapter_untitled
import app.tanuki.features.manga.resources.manga_chapters_filter
import app.tanuki.features.manga.resources.manga_chapters_sort
import app.tanuki.features.manga.resources.manga_chapters_total
import app.tanuki.features.manga.resources.manga_month_apr
import app.tanuki.features.manga.resources.manga_month_aug
import app.tanuki.features.manga.resources.manga_month_dec
import app.tanuki.features.manga.resources.manga_month_feb
import app.tanuki.features.manga.resources.manga_month_jan
import app.tanuki.features.manga.resources.manga_month_jul
import app.tanuki.features.manga.resources.manga_month_jun
import app.tanuki.features.manga.resources.manga_month_mar
import app.tanuki.features.manga.resources.manga_month_may
import app.tanuki.features.manga.resources.manga_month_nov
import app.tanuki.features.manga.resources.manga_month_oct
import app.tanuki.features.manga.resources.manga_month_sep
import app.tanuki.features.manga.resources.manga_no_chapters
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

/** Diameter of the leading unread indicator dot. */
private val UnreadDotSize = 8.dp

/** Direction-arrow size in the chapter sort toggle, shared by the filter glyph beside it. */
private val SortArrowSize = 18.dp

/** Diameter of the dot marking the filter action while a group is hidden. */
private val FilterDotSize = 6.dp

/**
 * Adds the chapters section — a header (title, count, and a sort-by-number toggle) followed by one row
 * per chapter — into a parent [LazyListScope]. Kept lazy (not a nested scroll) so a series with hundreds
 * of chapters only composes the rows on screen. Read chapters are dimmed; unread ones carry a leading
 * accent dot; tapping a row opens the reader at that chapter.
 *
 * @param chapters the chapters to list, already ordered and filtered by the ViewModel.
 * @param ascending the current sort direction, shown by the toggle's arrow.
 * @param canFilter whether more than one scanlation group published this series — the filter action is
 *   hidden otherwise, since a single-group series has nothing to choose between.
 * @param filterActive whether a group is currently hidden, marked with a dot on the filter action.
 * @param onToggleSort the sort toggle was tapped — flip ascending/descending.
 * @param onOpenFilter the filter action was tapped — open the scanlator filter sheet.
 * @param onChapterClick a chapter row was tapped — open it in the reader.
 */
internal fun LazyListScope.chapterList(
    chapters: List<Chapter>,
    ascending: Boolean,
    canFilter: Boolean,
    filterActive: Boolean,
    onToggleSort: () -> Unit,
    onOpenFilter: () -> Unit,
    onChapterClick: (Chapter) -> Unit
) {
    item(key = "chapters-header") {
        ChapterSectionHeader(
            count = chapters.size,
            ascending = ascending,
            canFilter = canFilter,
            filterActive = filterActive,
            onToggleSort = onToggleSort,
            onOpenFilter = onOpenFilter
        )
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
    canFilter: Boolean,
    filterActive: Boolean,
    onToggleSort: () -> Unit,
    onOpenFilter: () -> Unit
) {
    val spacing = TanukiTheme.spacing
    val sortDescription = stringResource(Res.string.manga_chapters_sort)
    val filterDescription = stringResource(Res.string.manga_chapters_filter)
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
        if (canFilter) {
            TanukiIconButton(
                onClick = onOpenFilter,
                modifier = Modifier.semantics { contentDescription = filterDescription }
            ) {
                // An active filter tints the glyph accent and adds a dot, the same signal the library
                // sheet's "Filter •" tab uses.
                val tint = if (filterActive) TanukiTheme.colors.accent else TanukiTheme.colors.textFaint
                Box(contentAlignment = Alignment.TopEnd) {
                    FilterIcon(tint = tint, size = SortArrowSize)
                    if (filterActive) {
                        Box(
                            Modifier
                                .size(FilterDotSize)
                                .clip(CircleShape)
                                .background(TanukiTheme.colors.accent)
                        )
                    }
                }
            }
        }
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
        Column(
            verticalArrangement = Arrangement.spacedBy(spacing.xxs),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = chapter.title(),
                style = TanukiTheme.typography.body,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // The source's own title, when the row's first line is already carrying the number.
            chapter.chapterNumber?.let { chapter.name?.value }?.let { name ->
                Text(
                    text = name,
                    style = TanukiTheme.typography.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            chapter.metaLine()?.let { meta ->
                Text(
                    text = meta,
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
 * The row's last line: upload date and scanlation group, joined when the source gives both, `null` when
 * it gives neither.
 *
 * The group is what makes an aggregator's list readable: MangaDex lists every group's take on the same
 * chapter number as a separate chapter, so without the credit those rows are indistinguishable and read
 * as duplicates. They are deliberately not merged — each is a real chapter with its own pages — so this
 * line is what turns a duplicate into a choice, and the date is how a reader judges between two of them.
 */
@Composable
private fun Chapter.metaLine(): String? {
    val date = dateUpload?.let { uploadDate(it) }
    val group = scanlator?.value
    return when {
        date != null && group != null -> stringResource(Res.string.manga_chapter_meta, date, group)
        else -> date ?: group
    }
}

/**
 * The upload date in the viewer's language — "Aug 3, 2024" in English, "3 de ago de 2024" in pt-BR.
 *
 * Assembled from a localized month abbreviation and a per-locale format string rather than a platform
 * date formatter: `kotlinx-datetime` has no localized formatting, and the two platforms' formatters
 * disagree, which would render the same chapter differently on Android and iOS. Month names follow the
 * activity feature's precedent of shipping abbreviations as string resources.
 *
 * The year is always shown: chapter lists routinely span a decade, and "Aug 3" alone is ambiguous
 * exactly where two groups' uploads are being compared.
 */
@Composable
private fun uploadDate(instant: Instant): String {
    val date = instant.toLocalDateTime(TimeZone.currentSystemDefault()).date
    return stringResource(Res.string.manga_chapter_date, monthAbbreviation(date.month), date.day, date.year)
}

/** Localized three-letter month abbreviations, indexed by [Month] ordinal (JANUARY = 0). */
@Composable
private fun monthAbbreviation(month: Month): String =
    listOf(
        stringResource(Res.string.manga_month_jan),
        stringResource(Res.string.manga_month_feb),
        stringResource(Res.string.manga_month_mar),
        stringResource(Res.string.manga_month_apr),
        stringResource(Res.string.manga_month_may),
        stringResource(Res.string.manga_month_jun),
        stringResource(Res.string.manga_month_jul),
        stringResource(Res.string.manga_month_aug),
        stringResource(Res.string.manga_month_sep),
        stringResource(Res.string.manga_month_oct),
        stringResource(Res.string.manga_month_nov),
        stringResource(Res.string.manga_month_dec)
    )[month.ordinal]

/** "4" for whole numbers, "4.5" otherwise — drops the trailing ".0" a raw [Double] would show. */
internal fun ChapterNumber.display(): String = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()