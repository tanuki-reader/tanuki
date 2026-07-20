package app.tanuki.features.manga.composable.components

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import app.tanuki.assets.designSystem.TanukiTheme
import app.tanuki.assets.designSystem.component.SectionHeader
import app.tanuki.assets.designSystem.component.SourceAvatar
import app.tanuki.assets.designSystem.component.TanukiButton
import app.tanuki.assets.designSystem.component.TanukiButtonVariant
import app.tanuki.core.domain.manga.model.Manga
import app.tanuki.core.domain.source.model.Source
import app.tanuki.features.manga.resources.Res
import app.tanuki.features.manga.resources.manga_info_add_to_library
import app.tanuki.features.manga.resources.manga_info_author
import app.tanuki.features.manga.resources.manga_info_description
import app.tanuki.features.manga.resources.manga_info_no_description
import app.tanuki.features.manga.resources.manga_info_remove_from_library
import app.tanuki.features.manga.resources.manga_info_source
import app.tanuki.features.manga.resources.manga_info_source_unknown
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
 */
@Composable
internal fun MangaInfoTab(
    manga: Manga,
    source: Source?,
    inLibrary: Boolean,
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
            Text(
                text = description ?: stringResource(Res.string.manga_info_no_description),
                style = TanukiTheme.typography.body,
                color =
                    if (description != null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        TanukiTheme.colors.textFaint
                    }
            )
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
        SourceAvatar(name = source.name.value, color = TanukiTheme.colors.accent)
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
