package app.tanuki.assets.designSystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.tanuki.assets.designSystem.TanukiRadii
import app.tanuki.assets.designSystem.TanukiTheme

/** Hairline outline of the input's resting (unfocused) state. */
private val FieldBorderWidth = 1.dp

/**
 * A single-line text input — the Tanuki design system's field, on the `surfaceVariant` input surface
 * (design token: *Surface2 — raised rows, chips, inputs*) with a tight container radius, brand type, and
 * an accent cursor. Replaces Material's `OutlinedTextField` so inputs read in the brand's tokens rather
 * than Material's floating-label/indicator styling.
 *
 * [placeholder] is already-resolved UI copy. Set [autoFocus] to focus and raise the keyboard on first
 * composition (e.g. in a create dialog); pass [onImeAction] to react to the keyboard action button (its
 * kind set by [imeAction]).
 *
 * @param value the current text.
 * @param onValueChange invoked as the text changes.
 * @param placeholder shown, in faint text, while [value] is empty.
 * @param singleLine whether the field is constrained to one line.
 * @param autoFocus whether to request focus (and show the keyboard) when first shown.
 * @param imeAction the keyboard action button kind (e.g. [ImeAction.Done]).
 * @param onImeAction invoked when the keyboard action button is pressed; also hides the keyboard.
 */
@Composable
fun TanukiTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    autoFocus: Boolean = false,
    imeAction: ImeAction = ImeAction.Default,
    onImeAction: (() -> Unit)? = null
) {
    val colors = TanukiTheme.colors
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    if (autoFocus) {
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
    }

    val onAction: () -> Unit = {
        onImeAction?.invoke()
        keyboardController?.hide()
    }

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().focusRequester(focusRequester),
        singleLine = singleLine,
        textStyle = TanukiTheme.typography.body.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(colors.accent),
        keyboardOptions = KeyboardOptions(imeAction = imeAction),
        keyboardActions =
            KeyboardActions(
                onDone = { onAction() },
                onGo = { onAction() },
                onSearch = { onAction() },
                onSend = { onAction() }
            ),
        decorationBox = { innerTextField ->
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier =
                    Modifier
                        .clip(TanukiRadii.chipShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(FieldBorderWidth, MaterialTheme.colorScheme.outlineVariant, TanukiRadii.chipShape)
                        .padding(horizontal = TanukiTheme.spacing.md, vertical = TanukiTheme.spacing.md)
            ) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = TanukiTheme.typography.body,
                        color = colors.textFaint
                    )
                }
                innerTextField()
            }
        }
    )
}

@Composable
@Preview
private fun TanukiTextFieldPreview() {
    TanukiTheme {
        TanukiTextField(value = "", onValueChange = {}, placeholder = "Category name")
    }
}