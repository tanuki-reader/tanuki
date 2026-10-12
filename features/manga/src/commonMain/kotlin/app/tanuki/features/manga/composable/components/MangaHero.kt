package app.tanuki.features.manga.composable.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tanuki.assets.designSystem.TanukiElevation
import app.tanuki.assets.designSystem.TanukiRadii
import app.tanuki.assets.designSystem.TanukiTheme
import app.tanuki.assets.designSystem.component.BookmarkIcon
import app.tanuki.assets.designSystem.component.ChevronDownIcon
import app.tanuki.assets.designSystem.component.LabelIcon
import app.tanuki.assets.designSystem.component.MangaCover
import app.tanuki.assets.designSystem.component.TanukiTag
import app.tanuki.assets.designSystem.component.coverImageRequest
import app.tanuki.core.domain.manga.model.Manga
import app.tanuki.core.domain.manga.model.PublishStatus
import app.tanuki.core.domain.manga.model.ReadingStatus
import app.tanuki.features.manga.resources.Res
import app.tanuki.features.manga.resources.manga_categories_add
import app.tanuki.features.manga.resources.manga_categories_more
import app.tanuki.features.manga.resources.manga_categories_title
import app.tanuki.features.manga.resources.manga_chapter_count_short
import app.tanuki.features.manga.resources.manga_reading_status_set
import app.tanuki.features.manga.resources.manga_status_cancelled
import app.tanuki.features.manga.resources.manga_status_completed
import app.tanuki.features.manga.resources.manga_status_hiatus
import app.tanuki.features.manga.resources.manga_status_licensed
import app.tanuki.features.manga.resources.manga_status_ongoing
import app.tanuki.features.manga.resources.manga_status_publishing_finished
import app.tanuki.features.manga.viewmodel.HeroLine
import app.tanuki.features.manga.viewmodel.MangaHeader
import coil3.compose.AsyncImage
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Width of the hero cover, a touch larger than the library grid tile so the detail reads as primary. */
private val HeroCoverWidth = 118.dp

private const val MAX_HERO_CATEGORY_CHIPS = 2

/** Size of the leading label glyph on the hero's category row. */
private val CategoryGlyphSize = 16.dp

/**
 * The detail hero, on the screen canvas: the [MangaCover] (the only cover with `shadow-cover`) beside the
 * title, publication-status chip, chapter count, and category chips. No blurred cover backdrop: Android
 * has no blur, and the art carries the colour (same as iOS). The author moves to the Info tab.
 *
 * @param manga the series to present.
 * @param header the merged header (title, cover) the page shows.
 * @param heroLine the one metadata line: status, chapter count, rating.
 */
@Composable
internal fun MangaHero(
    manga: Manga,
    header: MangaHeader,
    heroLine: HeroLine,
    categoryNames: List<String>,
    onManageCategories: () -> Unit,
    onEditReadingStatus: () -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = TanukiTheme.spacing
    val seed = manga.id.toString()
    // The merged header (matched metadata unless the user prefers the source's) — same as iOS.
    val coverUrl = header.coverUrl
    val coverHeaders = header.coverHeaders
    val statusLabel = heroLine.status?.labelResource()?.let { stringResource(it) }

    Box(modifier = modifier.fillMaxWidth()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    // Push the cover/title below the status bar, while the backdrop above bleeds behind it.
                    .statusBarsPadding()
                    .padding(start = spacing.gutter, end = spacing.gutter, bottom = spacing.xl, top = spacing.x6l)
        ) {
            MangaCover(
                seed = seed,
                modifier = Modifier.width(HeroCoverWidth).shadow(TanukiElevation.level3, TanukiRadii.coverShape)
            ) {
                // The real cover art (the hero previously showed only the gradient); null URL keeps it.
                coverUrl?.let { url ->
                    AsyncImage(
                        model = coverImageRequest(url, coverHeaders),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize()
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = header.title,
                    style = TanukiTheme.typography.hero,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                // Author lives in the Info tab (not the hero) so the hero stays lean; see MangaInfoTab.
                Spacer(Modifier.height(spacing.md))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                ) {
                    // The one metadata line: status · N ch. · ★ rating (the ViewModel's HeroLine).
                    statusLabel?.let { TanukiTag(text = it) }
                    Text(
                        text =
                            listOfNotNull(
                                pluralStringResource(
                                    Res.plurals.manga_chapter_count_short,
                                    heroLine.chapterCount,
                                    heroLine.chapterCount
                                ),
                                heroLine.rating?.let { "★ $it" }
                            ).joinToString(" · "),
                        style = TanukiTheme.typography.caption.copy(fontWeight = FontWeight.Medium),
                        color = TanukiTheme.colors.textFaint
                    )
                }
                Spacer(Modifier.height(spacing.md))
                ReadingStatusPill(status = manga.readingStatus, onClick = onEditReadingStatus)
                Spacer(Modifier.height(spacing.sm))
                CategoryChipsRow(names = categoryNames, onManage = onManageCategories)
            }
        }
    }
}

/**
 * The manga's category memberships as tappable chips — tapping anywhere opens the category picker. Capped
 * to [MAX_HERO_CATEGORY_CHIPS] chips plus a "+N" overflow chip so the row stays one line no matter how many
 * shelves the manga is on; an empty membership shows a single "Add to category" chip.
 */
@Composable
private fun CategoryChipsRow(
    names: List<String>,
    onManage: () -> Unit
) {
    val spacing = TanukiTheme.spacing
    val manageDescription = stringResource(Res.string.manga_categories_title)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        modifier = Modifier.clickable(onClick = onManage).semantics { contentDescription = manageDescription }
    ) {
        LabelIcon(tint = TanukiTheme.colors.textFaint, size = CategoryGlyphSize)
        if (names.isEmpty()) {
            TanukiTag(text = stringResource(Res.string.manga_categories_add))
        } else {
            names.take(MAX_HERO_CATEGORY_CHIPS).forEach { TanukiTag(text = it) }
            val overflow = names.size - MAX_HERO_CATEGORY_CHIPS
            if (overflow > 0) TanukiTag(text = stringResource(Res.string.manga_categories_more, overflow))
        }
    }
}

/** Leading glyph size on the reading-status pill, matched to the category row's [CategoryGlyphSize]. */
private val StatusGlyphSize = 16.dp

/** Trailing disclosure chevron on the reading-status pill — signals it opens the picker sheet. */
private val StatusChevronSize = 16.dp

/**
 * The user's reading status as a tappable pill that opens the status picker. When a status is set it reads
 * as an *active* selection — the accent-tint fill / accent text of a selected [TanukiFilterChip] — so the
 * user's own tracking state stands apart from the outline-only publication tag and category chips. With no
 * status yet (a manga not in the library) it's a subdued outline "Set reading status" invitation; picking
 * one implicitly adds the manga to the library (see the ViewModel).
 */
@Composable
private fun ReadingStatusPill(
    status: ReadingStatus?,
    onClick: () -> Unit
) {
    val spacing = TanukiTheme.spacing
    val selected = status != null
    // Selected: `ink` on `accent-tint` with the glyph in `accent`, as the design system's selected state.
    val contentColor = if (selected) MaterialTheme.colorScheme.onSurface else TanukiTheme.colors.textFaint
    val label =
        status?.let { stringResource(it.labelResource()) } ?: stringResource(Res.string.manga_reading_status_set)

    Row(
        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .clip(TanukiRadii.pillShape)
                .background(if (selected) TanukiTheme.colors.accentTint else Color.Transparent)
                .then(
                    if (selected) {
                        Modifier
                    } else {
                        Modifier.border(1.dp, MaterialTheme.colorScheme.outline, TanukiRadii.pillShape)
                    }
                ).clickable(onClick = onClick)
                .padding(horizontal = spacing.md, vertical = spacing.xs)
    ) {
        BookmarkIcon(
            tint = if (selected) TanukiTheme.colors.accent else contentColor,
            size = StatusGlyphSize,
            filled = selected,
        )
        Text(
            text = label,
            style = TanukiTheme.typography.caption.copy(fontWeight = FontWeight.Medium),
            color = contentColor
        )
        ChevronDownIcon(tint = contentColor, size = StatusChevronSize)
    }
}

/** Localized label for the publication-status chip; `null` (hides the chip) when the source gives none. */
private fun PublishStatus.labelResource(): StringResource? =
    when (this) {
        PublishStatus.ONGOING -> Res.string.manga_status_ongoing
        PublishStatus.COMPLETED -> Res.string.manga_status_completed
        PublishStatus.LICENSED -> Res.string.manga_status_licensed
        PublishStatus.PUBLISHING_FINISHED -> Res.string.manga_status_publishing_finished
        PublishStatus.CANCELLED -> Res.string.manga_status_cancelled
        PublishStatus.HIATUS -> Res.string.manga_status_hiatus
        PublishStatus.UNKNOWN -> null
    }