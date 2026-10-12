package app.tanuki.assets.designSystem.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.tanuki.assets.designSystem.TanukiRadii
import app.tanuki.assets.designSystem.TanukiSpacing
import app.tanuki.assets.designSystem.TanukiTheme

/** Square hit target from the design system's `IconButton` — honors the 44dp minimum tap area. */
private val IconButtonSize = TanukiSpacing.tapMin

/**
 * Icon-only tap target for app-bar actions and row controls — the **Android dialect** of the design
 * system's `IconButton`. Standard (transparent) variant: the glyph tints `text-1` normally and switches
 * to the tanuki orange accent when [active] (e.g. a filter with an active selection).
 *
 * [icon] is a slot given the resolved [Color] to draw with, so it composes with the [SearchIcon] /
 * [FilterIcon] / [CloseIcon] glyphs (or any other). Pass a `contentDescription` on the glyph for a11y.
 */
@Composable
fun TanukiIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    icon: @Composable (tint: Color) -> Unit
) {
    val tint = if (active) TanukiTheme.colors.accent else MaterialTheme.colorScheme.onSurface
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .size(IconButtonSize)
                .clip(TanukiRadii.pillShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick
                )
    ) {
        icon(tint)
    }
}

@Composable
@Preview
private fun TanukiIconButtonPreview() {
    TanukiTheme {
        TanukiIconButton(onClick = {}, active = true) { tint -> FilterIcon(tint = tint) }
    }
}