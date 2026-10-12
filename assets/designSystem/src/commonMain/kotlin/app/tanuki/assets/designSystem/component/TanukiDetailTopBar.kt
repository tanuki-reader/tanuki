package app.tanuki.assets.designSystem.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tanuki.assets.designSystem.TanukiSpacing
import app.tanuki.assets.designSystem.TanukiTheme

/** The design system's Android app-bar height (`--appbar-h`). */
private val AppBarHeight = TanukiSpacing.appBarHeight

/** Peak opacity of the circular scrim behind the back button while it floats over content. */
private const val FLOATING_BACK_SCRIM_ALPHA = 0.7f

/**
 * A back-navigating screen app bar — the **Android (Material You) dialect** of the design system's
 * `TopBar` `back` variant used by the Manga Detail and Reader screens.
 *
 * Supports the **collapsing** pattern via [collapsed]. When collapsed (the default) it is a solid bar:
 * a filled canvas-colored background, a hairline bottom divider, the back arrow, and the [title]. When
 * **not** collapsed it dissolves to transparent — the background, divider, and title fade out and the
 * back arrow gains a circular scrim so it stays legible floating over hero/cover art. Drive [collapsed]
 * from the screen's scroll position so the title only appears once the hero (which shows the big title)
 * has scrolled away — avoiding the title reading twice at once. The transition is animated.
 *
 * [title] is UI copy in the abstract, but callers pass the manga/chapter name — already-resolved content;
 * the component never inlines display literals. [actions] receives a [RowScope] for trailing controls.
 *
 * Optionally supports the **same in-place search** as [TanukiTopBar] (used by the source browse leaf, so
 * search feels identical to the library): pass [onSearchOpen] to add a leading search glyph; when
 * [searchActive] the bar swaps to an auto-focused inline field, its back arrow calling [onSearchClose]
 * (exit search) rather than [onBack] (exit screen). Searching forces the solid bar regardless of
 * [collapsed]. Leave [onSearchOpen] `null` for a plain back bar (the Manga Detail / Reader behavior).
 *
 * @param title the screen title shown next to the back arrow, truncated with an ellipsis when long.
 * @param onBack the back arrow was tapped — pop the back stack.
 * @param collapsed whether the bar is in its solid, titled state (`true`) or dissolved over content
 *   (`false`); animated between the two.
 * @param searchActive whether the inline search field is shown in place of the title.
 * @param searchQuery the current query (content, rendered verbatim).
 * @param onSearchOpen the search glyph was tapped; `null` omits search entirely (plain back bar).
 * @param onSearchClose the back arrow was tapped while searching — leave search, keep the screen.
 * @param onSearchQueryChange the query text changed.
 * @param searchPlaceholder resolved placeholder shown in the empty field.
 * @param onSearchSubmit the keyboard search action fired.
 * @param actions trailing action controls (typically [TanukiIconButton]s); empty by default.
 */
@Composable
fun TanukiDetailTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    collapsed: Boolean = true,
    searchActive: Boolean = false,
    searchQuery: String = "",
    onSearchOpen: (() -> Unit)? = null,
    onSearchClose: () -> Unit = {},
    onSearchQueryChange: (String) -> Unit = {},
    searchPlaceholder: String = "",
    onSearchSubmit: (String) -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {}
) {
    val colors = TanukiTheme.colors
    // 1f = fully collapsed (solid bar + title); 0f = dissolved (transparent, back floats on a scrim).
    // Searching forces the solid bar so the field is always legible.
    val collapse by animateFloatAsState(if (collapsed || searchActive) 1f else 0f, label = "detailBarCollapse")
    val containerColor = MaterialTheme.colorScheme.background
    val dividerColor = MaterialTheme.colorScheme.outlineVariant

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                // The fill + divider draw across the whole node (including the status-bar strip above),
                // so when collapsed the bar's background sits behind the status-bar icons. The content
                // below is inset past the status bar by windowInsetsPadding.
                .drawBehind {
                    drawRect(containerColor.copy(alpha = collapse))
                    drawLine(
                        color = dividerColor.copy(alpha = collapse),
                        start = Offset(0f, size.height),
                        end = Offset(size.width, size.height),
                        strokeWidth = 1.dp.toPx()
                    )
                }
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(AppBarHeight)
                .padding(horizontal = TanukiTheme.spacing.sm)
    ) {
        if (searchActive) {
            // Leading arrow exits search (not the screen), mirroring TanukiTopBar's library pattern.
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
                textStyle = TanukiTheme.typography.headline.copy(color = MaterialTheme.colorScheme.onSurface),
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
                TanukiIconButton(onClick = { onSearchQueryChange("") }) { tint -> CloseIcon(tint = tint) }
            }
        } else {
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .clip(CircleShape)
                        .background(containerColor.copy(alpha = (1f - collapse) * FLOATING_BACK_SCRIM_ALPHA))
            ) {
                TanukiIconButton(onClick = onBack) { tint -> BackIcon(tint = tint) }
            }
            Text(
                text = title,
                style = TanukiTheme.typography.headline,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(horizontal = TanukiTheme.spacing.sm)
                        .alpha(collapse)
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(TanukiTheme.spacing.xxs)
            ) {
                if (onSearchOpen != null) {
                    TanukiIconButton(onClick = onSearchOpen) { tint -> SearchIcon(tint = tint, size = 24.dp) }
                }
                actions()
            }
        }
    }
}
