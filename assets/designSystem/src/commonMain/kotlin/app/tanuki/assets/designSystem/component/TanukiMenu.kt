package app.tanuki.assets.designSystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.tanuki.assets.designSystem.TanukiElevation
import app.tanuki.assets.designSystem.TanukiRadii
import app.tanuki.assets.designSystem.TanukiTheme

/** Hairline around the menu, matching the one [TanukiDialog] draws around its card. */
private val MenuBorderWidth = 1.dp

/**
 * A dropdown menu wearing the app's surface rather than Material's.
 *
 * Stock `DropdownMenu` renders a tonally-elevated container with the default type scale, which on a
 * near-black canvas arrives as a visibly bluish, visibly *lighter* card that belongs to a different app
 * than the screen behind it. This pins the three things that give it away — the surface colour, the
 * corner radius, and the hairline border — to the same tokens [TanukiDialog] and the bottom sheets use,
 * so every transient surface in the app reads as one family.
 *
 * Tonal elevation is dropped to zero deliberately. Material derives a lighter fill from it, which is how
 * the stock menu gets its tint; depth here comes from the shadow and the border instead, exactly as it
 * does on a dialog.
 *
 * @param expanded whether the menu is showing.
 * @param onDismissRequest tapping outside, or pressing back.
 * @param content the items — [TanukiMenuItem]s.
 */
@Composable
fun TanukiMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        shape = TanukiRadii.containerShape,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = TanukiElevation.level0,
        shadowElevation = TanukiElevation.level3,
        border = BorderStroke(MenuBorderWidth, MaterialTheme.colorScheme.outlineVariant),
        content = content
    )
}

/**
 * One row of a [TanukiMenu], set in the app's body style.
 *
 * @param text the item's label. Copy, so always localized by the caller.
 * @param onClick the item was chosen. Callers close the menu themselves, since only they know whether the
 *   choice opens something else (a confirmation) or acts immediately.
 * @param destructive tints the label `danger`. For an item that removes something the user cannot get
 *   back without paying for it again — bytes over mobile data, in this app's case. Colour alone is never
 *   the whole signal: a destructive item is expected to be separated by a [TanukiMenuDivider] and, when
 *   it matters, to confirm.
 */
@Composable
fun TanukiMenuItem(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false
) {
    val color = if (destructive) TanukiTheme.colors.danger else MaterialTheme.colorScheme.onSurface
    DropdownMenuItem(
        text = {
            Text(
                text = text,
                style = TanukiTheme.typography.body,
                color = color
            )
        },
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors =
            MenuDefaults.itemColors(
                textColor = color,
                disabledTextColor = TanukiTheme.colors.textFaint
            ),
        contentPadding = PaddingValues(horizontal = TanukiTheme.spacing.lg, vertical = TanukiTheme.spacing.xs)
    )
}

/** A hairline between groups of [TanukiMenuItem]s — what sets a destructive item apart from the rest. */
@Composable
fun TanukiMenuDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier,
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

@Composable
@Preview
private fun TanukiMenuPreview() {
    TanukiTheme {
        Box(Modifier.size(240.dp, 160.dp)) {
            TanukiMenu(expanded = true, onDismissRequest = {}) {
                TanukiMenuItem(text = "Download next 5 chapters", onClick = {})
                TanukiMenuDivider()
                TanukiMenuItem(text = "Delete all downloads", onClick = {}, destructive = true)
            }
        }
    }
}
