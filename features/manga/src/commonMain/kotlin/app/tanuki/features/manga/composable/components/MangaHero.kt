package app.tanuki.features.manga.composable.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tanuki.assets.designSystem.TanukiElevation
import app.tanuki.assets.designSystem.TanukiRadii
import app.tanuki.assets.designSystem.TanukiTheme
import app.tanuki.assets.designSystem.component.LabelIcon
import app.tanuki.assets.designSystem.component.MangaCover
import app.tanuki.assets.designSystem.component.TanukiTag
import app.tanuki.assets.designSystem.coverGradient
import app.tanuki.core.domain.manga.model.Manga
import app.tanuki.core.domain.manga.model.PublishStatus
import coil3.compose.AsyncImage
import app.tanuki.features.manga.resources.Res
import app.tanuki.features.manga.resources.manga_categories_add
import app.tanuki.features.manga.resources.manga_categories_more
import app.tanuki.features.manga.resources.manga_categories_title
import app.tanuki.features.manga.resources.manga_chapter_count
import app.tanuki.features.manga.resources.manga_status_cancelled
import app.tanuki.features.manga.resources.manga_status_completed
import app.tanuki.features.manga.resources.manga_status_hiatus
import app.tanuki.features.manga.resources.manga_status_licensed
import app.tanuki.features.manga.resources.manga_status_ongoing
import app.tanuki.features.manga.resources.manga_status_publishing_finished
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Width of the hero cover, a touch larger than the library grid tile so the detail reads as primary. */
private val HeroCoverWidth = 118.dp

// Backdrop opacity + top-scrim are theme-aware because a *cover under a scrim* reads very differently on
// dark vs light: on the dark canvas the spec's dim 0.22 over a near-black scrim already shows the colours
// well, but on the light canvas that same wash sits under a cream scrim and washes out to ~13% cover
// ("basically white"). Light mode therefore needs a much higher cover opacity and a near-transparent top
// scrim so a comparable amount of the art actually shows.

/** Cover-backdrop opacity on the dark canvas (the design spec's dimmed 0.22 wash). */
private const val HeroBackdropAlphaDark = 0.22f

/** Cover-backdrop opacity on the light canvas — higher, to survive the cream scrim above it. */
private const val HeroBackdropAlphaLight = 0.55f

/** Top-of-backdrop scrim alpha on dark: a dark veil that still lets the colours through. */
private const val HeroScrimTopDark = 0.4f

/** Top-of-backdrop scrim alpha on light: near-transparent so the cover shows rather than the cream. */
private const val HeroScrimTopLight = 0.05f

/** Blur radius softening the cover backdrop so it reads as a colour wash, not legible art behind text. */
private val HeroBackdropBlur = 12.dp

private const val MAX_HERO_CATEGORY_CHIPS = 2

/** Size of the leading label glyph on the hero's category row. */
private val CategoryGlyphSize = 16.dp

/**
 * The detail hero: the cover art itself — blurred and heavily dimmed — washes the full-bleed backdrop so
 * the whole palette of the cover colours the header, fading into the screen canvas; an elevated
 * [MangaCover] is laid over it beside the title, publication-status chip, chapter count, and category
 * chips. This is the design system's `AndroidMangaDetailScreen` hero treatment (blurred cover backdrop,
 * not a single extracted colour); a series with no cover URL falls back to the deterministic
 * [coverGradient]. The author moves to the Info tab, and rating/genres from the reference are omitted
 * until the domain [Manga] model carries them.
 *
 * @param manga the series to present.
 * @param chapterCount how many chapters the manga has, shown next to the status chip.
 */
@Composable
internal fun MangaHero(
    manga: Manga,
    chapterCount: Int,
    categoryNames: List<String>,
    onManageCategories: () -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = TanukiTheme.spacing
    val background = MaterialTheme.colorScheme.background
    val darkTheme = isSystemInDarkTheme()
    val backdropAlpha = if (darkTheme) HeroBackdropAlphaDark else HeroBackdropAlphaLight
    val scrimTopAlpha = if (darkTheme) HeroScrimTopDark else HeroScrimTopLight
    val seed = manga.id.toString()
    val coverUrl = manga.cover.url?.value
    val statusLabel = manga.publishStatus.labelResource()?.let { stringResource(it) }

    Box(modifier = modifier.fillMaxWidth()) {
        // Cover-derived backdrop: the cover art, blurred and dimmed, so its colours wash the header.
        // Falls back to the deterministic gradient when the series has no cover URL.
        if (coverUrl != null) {
            AsyncImage(
                model = coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize().blur(HeroBackdropBlur).alpha(backdropAlpha)
            )
        } else {
            Box(Modifier.matchParentSize().alpha(backdropAlpha).background(coverGradient(seed)))
        }
        // Fade the backdrop into the screen canvas: a light scrim up top (so the cover shows), ramping to
        // the opaque background at the base — with enough veil behind the title band to keep it legible.
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0f to background.copy(alpha = scrimTopAlpha),
                        1f to background
                    )
                )
        )
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
                        model = url,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize()
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = manga.title.value,
                    style = TanukiTheme.typography.title,
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
                    statusLabel?.let { TanukiTag(text = it) }
                    Text(
                        text = pluralStringResource(Res.plurals.manga_chapter_count, chapterCount, chapterCount),
                        style = TanukiTheme.typography.caption.copy(fontWeight = FontWeight.Medium),
                        color = TanukiTheme.colors.textFaint
                    )
                }
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