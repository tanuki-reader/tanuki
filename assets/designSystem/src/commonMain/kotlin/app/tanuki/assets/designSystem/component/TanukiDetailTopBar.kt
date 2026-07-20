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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tanuki.assets.designSystem.TanukiTheme

/** The design system's Android app-bar height (`--appbar-h`). */
private val AppBarHeight = 56.dp

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
 * @param title the screen title shown next to the back arrow, truncated with an ellipsis when long.
 * @param onBack the back arrow was tapped — pop the back stack.
 * @param collapsed whether the bar is in its solid, titled state (`true`) or dissolved over content
 *   (`false`); animated between the two.
 * @param actions trailing action controls (typically [TanukiIconButton]s); empty by default.
 */
@Composable
fun TanukiDetailTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    collapsed: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {}
) {
    // 1f = fully collapsed (solid bar + title); 0f = dissolved (transparent, back floats on a scrim).
    val collapse by animateFloatAsState(if (collapsed) 1f else 0f, label = "detailBarCollapse")
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
            style = TanukiTheme.typography.title,
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
            horizontalArrangement = Arrangement.spacedBy(TanukiTheme.spacing.xxs),
            content = actions
        )
    }
}
