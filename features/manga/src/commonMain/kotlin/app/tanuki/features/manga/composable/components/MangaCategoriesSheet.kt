package app.tanuki.features.manga.composable.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.tanuki.assets.designSystem.TanukiTheme
import app.tanuki.assets.designSystem.component.TanukiBottomSheet
import app.tanuki.assets.designSystem.component.TanukiFilterChip
import app.tanuki.features.manga.resources.Res
import app.tanuki.features.manga.resources.manga_categories_empty
import app.tanuki.features.manga.resources.manga_categories_title
import app.tanuki.features.manga.viewmodel.MangaState.MangaCategoryChip
import org.jetbrains.compose.resources.stringResource
import kotlin.uuid.Uuid

/** Caps the sheet's chip area so a library with many categories scrolls instead of filling the screen. */
private val SheetContentMaxHeight = 420.dp

/**
 * Live-apply category picker for a manga — the design system's tabbed-sheet chip pattern (matching
 * `LibraryBottomSheet`). Every library category shows as a [TanukiFilterChip]; selected means this manga
 * is a member. Tapping a chip toggles membership immediately via [onToggle] (no Save step); the chips
 * re-derive from the reactive state. Many categories simply wrap and scroll within the sheet.
 *
 * @param categories every library category paired with this manga's membership.
 * @param onToggle a chip was tapped — flip the manga's membership in that category.
 * @param onDismiss the sheet was swiped away or its scrim tapped.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun MangaCategoriesSheet(
    categories: List<MangaCategoryChip>,
    onToggle: (Uuid) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = TanukiTheme.spacing
    TanukiBottomSheet(onDismissRequest = onDismiss, modifier = modifier) {
        Text(
            text = stringResource(Res.string.manga_categories_title),
            style = TanukiTheme.typography.title,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = spacing.gutter, vertical = spacing.xs)
        )
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .heightIn(max = SheetContentMaxHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = spacing.gutter)
                    .padding(top = spacing.sm, bottom = spacing.md)
        ) {
            if (categories.isEmpty()) {
                Text(
                    text = stringResource(Res.string.manga_categories_empty),
                    style = TanukiTheme.typography.body,
                    color = TanukiTheme.colors.textFaint,
                    modifier = Modifier.padding(vertical = spacing.sm)
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(spacing.sm)
                ) {
                    categories.forEach { chip ->
                        TanukiFilterChip(
                            text = chip.category.name.value,
                            selected = chip.isMember,
                            onClick = { onToggle(chip.category.id) }
                        )
                    }
                }
            }
        }
    }
}
