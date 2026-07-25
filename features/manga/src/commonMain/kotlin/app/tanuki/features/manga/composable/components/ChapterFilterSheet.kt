package app.tanuki.features.manga.composable.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import app.tanuki.assets.designSystem.TanukiRadii
import app.tanuki.assets.designSystem.TanukiTheme
import app.tanuki.assets.designSystem.component.TanukiButton
import app.tanuki.assets.designSystem.component.TanukiButtonVariant
import app.tanuki.assets.designSystem.component.TanukiFilterChip
import app.tanuki.features.manga.resources.Res
import app.tanuki.features.manga.resources.manga_filter_scanlators_help
import app.tanuki.features.manga.resources.manga_filter_section_scanlators
import app.tanuki.features.manga.resources.manga_filter_show_count
import app.tanuki.features.manga.resources.manga_filter_title
import app.tanuki.features.manga.resources.manga_sheet_reset
import app.tanuki.features.manga.viewmodel.MangaState.ScanlatorChip
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Caps the chip area so a series with many groups scrolls instead of filling the screen. */
private val SheetContentMaxHeight = 420.dp

/**
 * Chapter filter sheet — which scanlation groups' chapters the list shows.
 *
 * Wears the library filter sheet's chrome (title, group caption, chips, a Reset / "Show N" footer) but
 * has only the one section, so it skips the Sort/Filter tabs: chapter sorting already lives in the list
 * header's direction toggle.
 *
 * Selected means *shown*. It applies live — each tap re-derives the list underneath, and the footer's
 * "Show N" only dismisses — matching the library sheet and the category sheet's no-Save-step behavior.
 * The last shown group can't be turned off (the ViewModel ignores it), because an empty chapter list
 * with the sheet closed would look like a broken screen.
 *
 * @param scanlators every group that published a chapter, with its count and shown state.
 * @param visibleChapterCount chapters currently passing the filter — the footer's "Show N".
 * @param onToggle a group chip was tapped — show or hide that group's chapters.
 * @param onReset the reset control was tapped — show every group again.
 * @param onDismiss the sheet was swiped away, its scrim tapped, or "Show N" pressed.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ChapterFilterSheet(
    scanlators: List<ScanlatorChip>,
    visibleChapterCount: Int,
    onToggle: (String) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = TanukiTheme.spacing
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = TanukiRadii.sheetShape,
        containerColor = MaterialTheme.colorScheme.surface,
        scrimColor = TanukiTheme.colors.scrim
    ) {
        Text(
            text = stringResource(Res.string.manga_filter_title),
            style = TanukiTheme.typography.title,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = spacing.gutter, vertical = spacing.xs)
        )

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = SheetContentMaxHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = spacing.gutter)
                    .padding(top = spacing.sm, bottom = spacing.md)
        ) {
            GroupLabel(stringResource(Res.string.manga_filter_section_scanlators))
            Spacer(Modifier.size(spacing.xs))
            Text(
                text = stringResource(Res.string.manga_filter_scanlators_help),
                style = TanukiTheme.typography.caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = spacing.xxs, vertical = spacing.xs)
            )
            Spacer(Modifier.size(spacing.sm))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalArrangement = Arrangement.spacedBy(spacing.sm)
            ) {
                scanlators.forEach { chip ->
                    TanukiFilterChip(
                        // A group name is source content, never localized copy.
                        text = chip.name,
                        selected = chip.isShown,
                        count = chip.chapterCount,
                        onClick = { onToggle(chip.name) }
                    )
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = spacing.gutter, vertical = spacing.md),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TanukiButton(
                text = stringResource(Res.string.manga_sheet_reset),
                onClick = onReset,
                variant = TanukiButtonVariant.Text
            )
            TanukiButton(
                text =
                    pluralStringResource(
                        Res.plurals.manga_filter_show_count,
                        visibleChapterCount,
                        visibleChapterCount
                    ),
                onClick = onDismiss,
                variant = TanukiButtonVariant.Filled,
                block = true,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/** A small uppercase group caption, matching the design system's sheet `GroupLabel`. */
@Composable
private fun GroupLabel(text: String) {
    Text(
        text = text.uppercase(),
        style =
            TanukiTheme.typography.caption.copy(
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.06.em
            ),
        color = TanukiTheme.colors.textFaint,
        modifier = Modifier.padding(horizontal = TanukiTheme.spacing.xxs)
    )
}