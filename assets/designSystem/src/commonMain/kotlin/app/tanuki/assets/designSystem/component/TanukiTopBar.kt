package app.tanuki.assets.designSystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.tanuki.assets.designSystem.TanukiTheme

/** The design system's Android app-bar height (`--appbar-h`). */
private val AppBarHeight = 56.dp

/**
 * The screen app bar — the **Android (Material You) dialect** of the design system's `TopBar`. By default
 * it is **transparent**, sitting directly on the screen canvas so the whole screen reads as one surface
 * (Tanuki's near-monochrome, single-canvas library look). Pass a [containerColor] only when the bar needs
 * to read as raised chrome over scrolling content — it then fills with that color and gains a hairline
 * bottom border.
 *
 * Search is **not** a separate persistent field: [searchActive] toggles the bar in place between its two
 * faces — a title with a trailing search glyph (and an optional filter glyph), and an inline search field
 * with a leading back arrow and a trailing clear button (Mihon's pattern, matching the design system's
 * library screen). Entering search focuses the field automatically.
 *
 * [title] is content and [searchPlaceholder] is UI copy, so both are passed as **already-resolved**
 * strings (`stringResource(...)`) — the component never inlines display literals. [onSearchOpen] fires
 * when the search glyph is tapped; passing `null` **omits the search action entirely** for a title-only
 * bar (e.g. the Home screen). [onSearchClose] fires when the back arrow is tapped (the caller typically
 * also clears the query). Passing [onFilterClick] adds a filter action next to search in the title face
 * (hidden while searching); [filterActive] tints it orange to signal an active selection.
 */
@Composable
fun TanukiTopBar(
    title: String,
    searchActive: Boolean,
    searchQuery: String,
    onSearchOpen: (() -> Unit)?,
    onSearchClose: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    searchPlaceholder: String = "",
    onSearchSubmit: (String) -> Unit = {},
    onFilterClick: (() -> Unit)? = null,
    filterActive: Boolean = false,
    containerColor: Color = Color.Unspecified
) {
    val colors = TanukiTheme.colors
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .height(AppBarHeight)
                .then(
                    if (containerColor.isSpecified) {
                        Modifier
                            .background(containerColor)
                            .drawBehind {
                                drawLine(
                                    color = borderColor,
                                    start = Offset(0f, size.height),
                                    end = Offset(size.width, size.height),
                                    strokeWidth = 1f
                                )
                            }
                    } else {
                        Modifier
                    }
                )
                .padding(horizontal = TanukiTheme.spacing.sm)
    ) {
        if (searchActive) {
            TanukiIconButton(onClick = onSearchClose) { tint -> BackIcon(tint = tint) }
            val focusRequester = remember { FocusRequester() }
            val keyboardController = LocalSoftwareKeyboardController.current
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
            BasicTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(horizontal = TanukiTheme.spacing.xs)
                        .focusRequester(focusRequester),
                singleLine = true,
                textStyle =
                    TanukiTheme.typography.headline.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(colors.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions =
                    KeyboardActions(
                        onSearch = {
                            onSearchSubmit(searchQuery)
                            keyboardController?.hide()
                        }
                    ),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (searchQuery.isEmpty()) {
                            Text(
                                text = searchPlaceholder,
                                style = TanukiTheme.typography.headline,
                                color = colors.textFaint

                            )
                        }
                        innerTextField()
                    }
                }
            )
            if (searchQuery.isNotEmpty()) {
                TanukiIconButton(onClick = { onSearchQueryChange("") }) { tint ->
                    CloseIcon(tint = tint)
                }
            }
        } else {
            Text(
                text = title,
                style = TanukiTheme.typography.title,
                color = MaterialTheme.colorScheme.onSurface,
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(horizontal = TanukiTheme.spacing.sm)
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(TanukiTheme.spacing.xxs)
            ) {
                if (onSearchOpen != null) {
                    TanukiIconButton(onClick = onSearchOpen) { tint -> SearchIcon(tint = tint, size = 24.dp) }
                }
                if (onFilterClick != null) {
                    TanukiIconButton(onClick = onFilterClick, active = filterActive) { tint ->
                        FilterIcon(tint = tint)
                    }
                }
            }
        }
    }
}

@Composable
@Preview
private fun TanukiTopBarTitlePreview() {
    TanukiTheme {
        var filterActive by remember { mutableStateOf(true) }
        TanukiTopBar(
            title = "Library",
            searchActive = false,
            searchQuery = "",
            onSearchOpen = {},
            onSearchClose = {},
            onSearchQueryChange = {},
            onFilterClick = { filterActive = !filterActive },
            filterActive = filterActive
        )
    }
}

@Composable
@Preview
private fun TanukiTopBarSearchPreview() {
    TanukiTheme {
        var query by remember { mutableStateOf("berserk") }
        TanukiTopBar(
            title = "Library",
            searchActive = true,
            searchQuery = query,
            onSearchOpen = {},
            onSearchClose = {},
            onSearchQueryChange = { query = it },
            searchPlaceholder = "Search library…"
        )
    }
}