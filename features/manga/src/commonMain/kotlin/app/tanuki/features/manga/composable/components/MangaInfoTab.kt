package app.tanuki.features.manga.composable.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import app.tanuki.assets.designSystem.TanukiTheme
import app.tanuki.assets.designSystem.component.ChevronRightIcon
import app.tanuki.assets.designSystem.component.SectionHeader
import app.tanuki.assets.designSystem.component.SourceIcon
import app.tanuki.assets.designSystem.component.TanukiButton
import app.tanuki.assets.designSystem.component.TanukiButtonVariant
import app.tanuki.core.domain.manga.model.Manga
import app.tanuki.core.domain.manga.model.ReadingMode
import app.tanuki.core.domain.source.model.Source
import app.tanuki.features.manga.resources.Res
import app.tanuki.features.manga.resources.manga_info_add_to_library
import app.tanuki.features.manga.resources.manga_info_author
import app.tanuki.features.manga.resources.manga_info_description
import app.tanuki.features.manga.resources.manga_info_no_description
import app.tanuki.features.manga.resources.manga_info_remove_from_library
import app.tanuki.features.manga.resources.manga_info_show_less
import app.tanuki.features.manga.resources.manga_info_show_more
import app.tanuki.features.manga.resources.manga_info_source
import app.tanuki.features.manga.resources.manga_info_source_unknown
import app.tanuki.features.manga.resources.manga_reading_mode_title
import org.jetbrains.compose.resources.stringResource

/**
 * The detail body's **Info** pane: the manga's synopsis, its author (when the source provides one), the
 * source it came from, and the library toggle. The button is an "Add to library" (accent) when the manga
 * isn't in the library and a destructive "Remove from library" (danger) when it is — a removal drops the
 * membership and all category assignments (see the ViewModel), leaving the manga browsable.
 *
 * Scrolls its own content (kept short) independently of the Chapters pane. All display copy is resolved
 * here per the localization boundary — nothing user-facing is passed in as a raw string.
 *
 * @param manga the series whose metadata to present.
 * @param source the manga's source, or `null` when it isn't persisted locally (shows a fallback label).
 * @param inLibrary whether the manga is currently in the library — picks which library action to show.
 * @param onEditReadingMode the reading-mode row was tapped — open the picker sheet. Lives here (not the
 *   hero) because it is a reading preference that applies whether or not the manga is in the library.
 */
@Composable
internal fun MangaInfoTab(
    manga: Manga,
    source: Source?,
    inLibrary: Boolean,
    onEditReadingMode: () -> Unit,
    onAddToLibrary: () -> Unit,
    onRemoveFromLibrary: () -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = TanukiTheme.spacing
    Column(
        modifier =
            modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.gutter)
                .padding(top = spacing.lg, bottom = spacing.xl),
        verticalArrangement = Arrangement.spacedBy(spacing.xl)
    ) {
        InfoSection(title = stringResource(Res.string.manga_info_description)) {
            val description = manga.description?.value
            if (description != null) {
                ExpandableDescription(text = description)
            } else {
                Text(
                    text = stringResource(Res.string.manga_info_no_description),
                    style = TanukiTheme.typography.body,
                    color = TanukiTheme.colors.textFaint
                )
            }
        }

        manga.author?.let { author ->
            InfoSection(title = stringResource(Res.string.manga_info_author)) {
                Text(
                    text = author.value,
                    style = TanukiTheme.typography.body,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        InfoSection(title = stringResource(Res.string.manga_info_source)) {
            SourceRow(source)
        }

        InfoSection(title = stringResource(Res.string.manga_reading_mode_title)) {
            ReadingModeRow(mode = manga.readingMode, onClick = onEditReadingMode)
        }

        if (inLibrary) {
            TanukiButton(
                text = stringResource(Res.string.manga_info_remove_from_library),
                onClick = onRemoveFromLibrary,
                variant = TanukiButtonVariant.Danger,
                block = true,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            TanukiButton(
                text = stringResource(Res.string.manga_info_add_to_library),
                onClick = onAddToLibrary,
                variant = TanukiButtonVariant.Filled,
                block = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * The synopsis, clamped to [COLLAPSED_MAX_LINES] until tapped. A "Show more"/"Show less" toggle appears
 * only when the text actually overflows the clamp — a short synopsis renders in full with no affordance.
 * The whole block is tappable so the toggle isn't the only hit target.
 */
@Composable
private fun ExpandableDescription(text: String) {
    var expanded by remember { mutableStateOf(false) }
    // Tracks whether the collapsed render clipped any text; drives whether the toggle is shown at all.
    var isClamped by remember { mutableStateOf(false) }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(enabled = expanded || isClamped) { expanded = !expanded }
                .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(TanukiTheme.spacing.xs)
    ) {
        Text(
            text = text,
            style = TanukiTheme.typography.body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else COLLAPSED_MAX_LINES,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { result ->
                // Once expanded the clamp no longer applies, so keep the last known clamped state.
                if (!expanded) isClamped = result.hasVisualOverflow
            }
        )
        if (expanded || isClamped) {
            Text(
                text =
                    stringResource(
                        if (expanded) Res.string.manga_info_show_less else Res.string.manga_info_show_more
                    ),
                style = TanukiTheme.typography.label,
                color = TanukiTheme.colors.accent
            )
        }
    }
}

/** Lines of synopsis shown before the "Show more" toggle collapses the rest. */
private const val COLLAPSED_MAX_LINES = 4

/** A titled block: a [SectionHeader] over its content, spaced by the design system's small gap. */
@Composable
private fun InfoSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(TanukiTheme.spacing.sm)) {
        SectionHeader(title = title)
        content()
    }
}

/**
 * The current reading mode as a tappable value row — its localized label with a drill-down chevron, the
 * whole row opening the picker sheet via [onClick]. Composed from the design system's primitives (like
 * [SourceRow] above) so it keeps the Info tab's gutter alignment, rather than a full-bleed `TanukiListRow`.
 */
@Composable
private fun ReadingModeRow(
    mode: ReadingMode,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TanukiTheme.spacing.md),
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
    ) {
        Text(
            text = stringResource(mode.labelResource()),
            style = TanukiTheme.typography.body,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        ChevronRightIcon(tint = TanukiTheme.colors.textFaint)
    }
}

/** The source identity: its avatar beside its name and language, or a fallback when not resolved. */
@Composable
private fun SourceRow(source: Source?) {
    val spacing = TanukiTheme.spacing
    if (source == null) {
        Text(
            text = stringResource(Res.string.manga_info_source_unknown),
            style = TanukiTheme.typography.body,
            color = TanukiTheme.colors.textFaint
        )
        return
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.md)
    ) {
        // The lettered avatar still shows through while the icon loads, and for a source that reports
        // none (or was installed before the URL was persisted) it is the whole badge.
        SourceIcon(
            name = source.name.value,
            iconUrl = source.iconUrl?.value,
            color = TanukiTheme.colors.accent
        )
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = source.name.value,
                style = TanukiTheme.typography.body,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = source.lang.value,
                style = TanukiTheme.typography.caption,
                color = TanukiTheme.colors.textFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}