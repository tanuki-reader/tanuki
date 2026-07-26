package app.tanuki.features.manga.composable.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.tanuki.assets.designSystem.TanukiRadii
import app.tanuki.assets.designSystem.TanukiTheme
import app.tanuki.assets.designSystem.component.TanukiFilterChip
import app.tanuki.core.domain.manga.model.ReadingMode
import app.tanuki.features.manga.resources.Res
import app.tanuki.features.manga.resources.manga_reading_mode_continuous_vertical
import app.tanuki.features.manga.resources.manga_reading_mode_left_to_right
import app.tanuki.features.manga.resources.manga_reading_mode_right_to_left
import app.tanuki.features.manga.resources.manga_reading_mode_title
import app.tanuki.features.manga.resources.manga_reading_mode_vertical
import app.tanuki.features.manga.resources.manga_reading_mode_webtoon
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Live-apply reading-mode picker for a manga — the design system's chip-in-a-sheet pattern shared with
 * [ReadingStatusSheet]. Every [ReadingMode] shows as a [TanukiFilterChip]; the [current] one is selected.
 * Single-select: tapping a chip records that mode via [onSelect] and closes the sheet, and the reactive
 * state re-derives the Info tab's value row.
 *
 * Reading mode is a source-owned reading preference that exists whether or not the manga is in the library
 * (see [app.tanuki.core.domain.manga.usecase.SetReadingMode]), so — unlike categories/status — choosing one
 * never adds the manga to the library, and there is no "none"/clear option: a manga always has a mode.
 *
 * @param current the manga's current reading mode.
 * @param onSelect a mode chip was tapped — record it for this manga.
 * @param onDismiss the sheet was swiped away or its scrim tapped.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ReadingModeSheet(
    current: ReadingMode,
    onSelect: (ReadingMode) -> Unit,
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
            text = stringResource(Res.string.manga_reading_mode_title),
            style = TanukiTheme.typography.title,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = spacing.gutter, vertical = spacing.xs)
        )
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = spacing.gutter)
                    .padding(top = spacing.sm, bottom = spacing.md)
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalArrangement = Arrangement.spacedBy(spacing.sm)
            ) {
                ReadingMode.entries.forEach { mode ->
                    TanukiFilterChip(
                        text = stringResource(mode.labelResource()),
                        selected = mode == current,
                        onClick = { onSelect(mode) }
                    )
                }
            }
        }
    }
}

/** Localized label for a reading mode, shared by the picker sheet and the Info tab's value row. */
internal fun ReadingMode.labelResource(): StringResource =
    when (this) {
        ReadingMode.LEFT_TO_RIGHT -> Res.string.manga_reading_mode_left_to_right
        ReadingMode.RIGHT_TO_LEFT -> Res.string.manga_reading_mode_right_to_left
        ReadingMode.WEBTOON -> Res.string.manga_reading_mode_webtoon
        ReadingMode.VERTICAL -> Res.string.manga_reading_mode_vertical
        ReadingMode.CONTINUOUS_VERTICAL -> Res.string.manga_reading_mode_continuous_vertical
    }