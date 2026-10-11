package app.tanuki.assets.designSystem.component

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.tanuki.assets.designSystem.TanukiRadii
import app.tanuki.assets.designSystem.TanukiTheme

/**
 * A modal bottom sheet wearing the app's surface rather than Material's.
 *
 * Stock `ModalBottomSheet` paints `surfaceContainerLow`, a role the Tanuki color scheme doesn't define, so
 * it falls back to Material's baseline palette and reads as a different app. This pins the surface, the
 * `radius-xl` top corners and the scrim to the design system, the same family as [TanukiMenu] and
 * [TanukiDialog].
 *
 * @param skipPartiallyExpanded open straight to full height. Short pickers want it; long, scrolling
 *   content (a chapter list, a synopsis) reads better opening half-way.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TanukiBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    skipPartiallyExpanded: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = skipPartiallyExpanded),
        shape = TanukiRadii.sheetShape,
        containerColor = MaterialTheme.colorScheme.surface,
        scrimColor = TanukiTheme.colors.scrim,
        content = content
    )
}
