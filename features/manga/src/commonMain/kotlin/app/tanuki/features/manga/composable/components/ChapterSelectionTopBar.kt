package app.tanuki.features.manga.composable.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.tanuki.assets.designSystem.component.SelectAllIcon
import app.tanuki.assets.designSystem.component.TanukiIconButton
import app.tanuki.assets.designSystem.component.TanukiSelectionTopBar
import app.tanuki.features.manga.resources.Res
import app.tanuki.features.manga.resources.manga_selection_count
import app.tanuki.features.manga.resources.manga_selection_deselect_all
import app.tanuki.features.manga.resources.manga_selection_exit
import app.tanuki.features.manga.resources.manga_selection_select_all
import app.tanuki.features.manga.viewmodel.MangaState.ChapterSelection
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The Chapters tab's contextual app bar: the running count, a way out, and select-all.
 *
 * Select-all lives up here rather than in the bottom [app.tanuki.features.manga.composable.ChapterSelectionBar]
 * on purpose — it's a *scoping* control like the count beside it ("how much is picked"), while the bottom
 * bar holds the verbs that act on that scope. It's also the one control that reverses itself, so it stays
 * next to the count that tells the user which way it will go.
 *
 * @param selection the live selection — its count titles the bar and its `allSelected` flips select-all
 *   into "deselect all".
 * @param onClose the X was tapped — leave selection mode.
 * @param onToggleSelectAll select-all was tapped — pick every shown chapter, or clear them all.
 */
@Composable
internal fun ChapterSelectionTopBar(
    selection: ChapterSelection,
    onClose: () -> Unit,
    onToggleSelectAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    val selectAllDescription =
        if (selection.allSelected) {
            stringResource(Res.string.manga_selection_deselect_all)
        } else {
            stringResource(Res.string.manga_selection_select_all)
        }
    TanukiSelectionTopBar(
        title = pluralStringResource(Res.plurals.manga_selection_count, selection.count, selection.count),
        onClose = onClose,
        closeDescription = stringResource(Res.string.manga_selection_exit),
        modifier = modifier
    ) {
        TanukiIconButton(
            onClick = onToggleSelectAll,
            // Tinted accent once everything is picked — the same "this control is engaged" language the
            // chapter list's filter glyph uses.
            active = selection.allSelected,
            modifier = Modifier.semantics { contentDescription = selectAllDescription }
        ) { tint ->
            SelectAllIcon(tint = tint)
        }
    }
}