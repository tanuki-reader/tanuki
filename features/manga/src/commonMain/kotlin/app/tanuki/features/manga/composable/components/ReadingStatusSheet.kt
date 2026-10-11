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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.tanuki.assets.designSystem.TanukiTheme
import app.tanuki.assets.designSystem.component.TanukiBottomSheet
import app.tanuki.assets.designSystem.component.TanukiFilterChip
import app.tanuki.core.domain.manga.model.ReadingStatus
import app.tanuki.features.manga.resources.Res
import app.tanuki.features.manga.resources.manga_reading_status_completed
import app.tanuki.features.manga.resources.manga_reading_status_dropped
import app.tanuki.features.manga.resources.manga_reading_status_on_hold
import app.tanuki.features.manga.resources.manga_reading_status_planning
import app.tanuki.features.manga.resources.manga_reading_status_reading
import app.tanuki.features.manga.resources.manga_reading_status_title
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Live-apply reading-status picker for a manga — the design system's tabbed-sheet chip pattern (matching
 * [MangaCategoriesSheet] and the library's status filter). Every [ReadingStatus] shows as a
 * [TanukiFilterChip]; the [current] one is selected. Unlike categories this is **single-select**: tapping
 * a chip records that status via [onSelect] and closes the sheet — the reactive state re-derives the pill.
 *
 * Choosing a status for a manga that isn't in the library implicitly adds it first (handled downstream in
 * the ViewModel/use case), so there is deliberately no "none"/clear option here — removal is "Remove from
 * library" on the Info tab.
 *
 * @param current the manga's reading status, or `null` when it isn't in the library / has none set.
 * @param onSelect a status chip was tapped — record it for this manga.
 * @param onDismiss the sheet was swiped away or its scrim tapped.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ReadingStatusSheet(
    current: ReadingStatus?,
    onSelect: (ReadingStatus) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = TanukiTheme.spacing
    TanukiBottomSheet(onDismissRequest = onDismiss, modifier = modifier) {
        Text(
            text = stringResource(Res.string.manga_reading_status_title),
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
                ReadingStatus.entries.forEach { status ->
                    TanukiFilterChip(
                        text = stringResource(status.labelResource()),
                        selected = status == current,
                        onClick = { onSelect(status) }
                    )
                }
            }
        }
    }
}

/** Localized label for a reading status; copy mirrors the library's status filter for consistency. */
internal fun ReadingStatus.labelResource(): StringResource =
    when (this) {
        ReadingStatus.READING -> Res.string.manga_reading_status_reading
        ReadingStatus.COMPLETED -> Res.string.manga_reading_status_completed
        ReadingStatus.ON_HOLD -> Res.string.manga_reading_status_on_hold
        ReadingStatus.DROPPED -> Res.string.manga_reading_status_dropped
        ReadingStatus.PLANNING_TO_READ -> Res.string.manga_reading_status_planning
    }