package app.tanuki.assets.designSystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.tanuki.assets.designSystem.TanukiRadii
import app.tanuki.assets.designSystem.TanukiTheme

/** Hairline that outlines the dialog card against the scrim. */
private val DialogBorderWidth = 1.dp

/**
 * A centered modal dialog — the Tanuki design system's replacement for Material's `AlertDialog`, so
 * confirmations and short inputs read in the brand's tokens (surface, tight radius, [TanukiTextStyles],
 * [TanukiButton] actions) instead of Material defaults. Built on the raw Compose [Dialog] primitive.
 *
 * Layout: a [title], an optional [content] body (a message, or an input such as [TanukiTextField]), and a
 * trailing action row — an optional [dismissText] ghost button and the [confirmText] primary. Use
 * [confirmVariant] = [TanukiButtonVariant.Danger] for destructive confirmations (the hairline-danger
 * treatment, never a loud filled red) and [confirmEnabled] to gate the primary until input is valid.
 *
 * All strings are already-resolved UI copy (`stringResource(...)`); the component never inlines literals.
 *
 * @param title the dialog heading.
 * @param onDismiss invoked on the dismiss button, a back press, or a tap outside the card.
 * @param confirmText label of the primary action.
 * @param onConfirm invoked when the primary action is tapped.
 * @param dismissText label of the secondary/ghost action; `null` shows only the primary.
 * @param confirmEnabled whether the primary action is enabled (e.g. `false` while an input is blank).
 * @param confirmVariant fill of the primary action — [TanukiButtonVariant.Filled] by default,
 *   [TanukiButtonVariant.Danger] for destructive confirmations.
 * @param content optional body between the title and the actions.
 */
@Composable
fun TanukiDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmText: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    dismissText: String? = null,
    confirmEnabled: Boolean = true,
    confirmVariant: TanukiButtonVariant = TanukiButtonVariant.Filled,
    content: (@Composable ColumnScope.() -> Unit)? = null
) {
    val spacing = TanukiTheme.spacing
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier =
                modifier
                    .fillMaxWidth()
                    .clip(TanukiRadii.dialogShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(DialogBorderWidth, MaterialTheme.colorScheme.outlineVariant, TanukiRadii.dialogShape)
                    .padding(spacing.xl),
            verticalArrangement = Arrangement.spacedBy(spacing.md)
        ) {
            Text(
                text = title,
                style = TanukiTheme.typography.title,
                color = MaterialTheme.colorScheme.onSurface
            )
            content?.invoke(this)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (dismissText != null) {
                    TanukiButton(
                        text = dismissText,
                        onClick = onDismiss,
                        variant = TanukiButtonVariant.Text
                    )
                }
                TanukiButton(
                    text = confirmText,
                    onClick = onConfirm,
                    variant = confirmVariant,
                    enabled = confirmEnabled
                )
            }
        }
    }
}

@Composable
@Preview
private fun TanukiDialogPreview() {
    TanukiTheme {
        TanukiDialog(
            title = "Delete category?",
            onDismiss = {},
            dismissText = "Cancel",
            confirmText = "Delete",
            confirmVariant = TanukiButtonVariant.Danger,
            onConfirm = {}
        ) {
            Text(
                text = "“Action” will be removed from all its manga.",
                style = TanukiTheme.typography.body,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}