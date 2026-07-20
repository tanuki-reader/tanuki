package app.tanuki.assets.designSystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.tooling.preview.Preview
import app.tanuki.assets.designSystem.TanukiTheme

/**
 * A screen header pairing the [TanukiSearchBar] (taking the available width) with a trailing filter
 * button ([TanukiIconButton] + [FilterIcon]) — the inline-search layout used for library / browse
 * screens. Pads to the screen gutter so it drops straight in above a grid or list.
 *
 * By default the header is **transparent** and sits directly on the screen canvas (`background`), so the
 * only filled element is the search pill (`surface-2`) — matching the design system's library screen,
 * where the whole screen is one `background` canvas rather than a stack of surface bars. Pass an explicit
 * [containerColor] only when the header genuinely needs to read as a raised bar over scrolling content.
 *
 * [placeholder] is UI copy — pass an **already-resolved localized string** (`stringResource(...)`).
 * [filterActive] tints the filter glyph orange to signal an active selection; [onFilterClick] typically
 * opens the filter sheet. [onSubmit] fires on the keyboard's search action.
 */
@Composable
fun TanukiSearchHeader(
    query: String,
    onQueryChange: (String) -> Unit,
    onFilterClick: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    filterActive: Boolean = false,
    containerColor: Color = Color.Unspecified,
    onSubmit: (String) -> Unit = {}
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TanukiTheme.spacing.sm),
        modifier =
            modifier
                .fillMaxWidth()
                .let { if (containerColor.isSpecified) it.background(containerColor) else it }
                .padding(horizontal = TanukiTheme.spacing.gutter, vertical = TanukiTheme.spacing.sm)
    ) {
        TanukiSearchBar(
            value = query,
            onValueChange = onQueryChange,
            placeholder = placeholder,
            onSubmit = onSubmit,
            modifier = Modifier.weight(1f)
        )
        TanukiIconButton(onClick = onFilterClick, active = filterActive) { tint ->
            FilterIcon(tint = tint)
        }
    }
}

@Composable
@Preview
private fun TanukiSearchHeaderPreview() {
    TanukiTheme {
        var query by remember { mutableStateOf("") }
        Box(modifier = Modifier.background(MaterialTheme.colorScheme.background)) {
            TanukiSearchHeader(
                query = query,
                onQueryChange = { query = it },
                onFilterClick = {},
                placeholder = "Search library…",
                filterActive = true
            )
        }
    }
}