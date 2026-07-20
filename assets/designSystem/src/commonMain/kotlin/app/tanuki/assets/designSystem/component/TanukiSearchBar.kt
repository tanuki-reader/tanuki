package app.tanuki.assets.designSystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.tanuki.assets.designSystem.TanukiRadii
import app.tanuki.assets.designSystem.TanukiTheme

/** The pill's fixed height, from the design system's `SearchBar` (Android dialect). */
private val SearchBarHeight = 40.dp

/**
 * Source / library search field — the **Android (Material You) dialect** of the design system's
 * `SearchBar`: a pill-shaped chip on a mid-contrast `surface-2` fill, with a leading search glyph, an
 * inline text field, and a trailing clear button that appears once there's text. (The iOS Liquid Glass
 * dialect is reference only; one Compose component serves both platforms.)
 *
 * [placeholder] is UI copy, so the caller passes an **already-resolved localized string**
 * (`stringResource(...)`) — the component never inlines display literals. [value] is the current query
 * (content, rendered verbatim). [onSubmit] fires on the keyboard's search action.
 */
@Composable
fun TanukiSearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    onSubmit: (String) -> Unit = {}
) {
    val colors = TanukiTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TanukiTheme.spacing.sm),
        modifier =
            modifier
                .height(SearchBarHeight)
                .clip(TanukiRadii.pillShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = TanukiTheme.spacing.md)
    ) {
        SearchIcon(tint = colors.textFaint)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            textStyle = TanukiTheme.typography.body.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit(value) }),
            decorationBox = { innerTextField ->
                Box(contentAlignment = Alignment.CenterStart) {
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
        if (value.isNotEmpty()) {
            CloseIcon(
                tint = colors.textFaint,
                modifier =
                    Modifier
                        .clip(TanukiRadii.pillShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onValueChange("") }
                        )
            )
        }
    }
}

@Composable
@Preview
private fun TanukiSearchBarPreview() {
    TanukiTheme {
        var query by remember { mutableStateOf("") }
        Box(modifier = Modifier.background(MaterialTheme.colorScheme.background).padding(16.dp)) {
            TanukiSearchBar(
                value = query,
                onValueChange = { query = it },
                placeholder = "Search library…"
            )
        }
    }
}

@Composable
@Preview
private fun TanukiSearchBarFilledPreview() {
    TanukiTheme {
        var query by remember { mutableStateOf("Berserk") }
        Box(modifier = Modifier.background(MaterialTheme.colorScheme.background).padding(16.dp)) {
            TanukiSearchBar(value = query, onValueChange = { query = it })
        }
    }
}