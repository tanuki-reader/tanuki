package app.tanuki.assets.designSystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tanuki.assets.designSystem.TanukiRadii
import app.tanuki.assets.designSystem.TanukiSpacing
import app.tanuki.assets.designSystem.TanukiTheme

/** Tanuki's primary action control's fills (design system `Button` `variant`). */
enum class TanukiButtonVariant { Filled, Tonal, Text, Outline, Danger }

/** Button heights from the design system `Button` `size` scale. */
enum class TanukiButtonSize { Small, Medium, Large }

/**
 * Tanuki's primary action control — the Android (Material, tight-radius) dialect of the design system's
 * `Button`. [variant] picks the fill (orange [TanukiButtonVariant.Filled], raised
 * [TanukiButtonVariant.Tonal], ghost [TanukiButtonVariant.Text], hairline [TanukiButtonVariant.Outline],
 * or a destructive danger-outlined [TanukiButtonVariant.Danger]); [size] sets the height/padding;
 * [block] stretches it to fill its width (for a footer's primary action). Corners use the Material
 * container radius.
 *
 * [text] is UI copy — pass an already-resolved localized string. Icons are intentionally omitted for now;
 * add a leading-glyph slot when a caller needs one.
 */
@Composable
fun TanukiButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: TanukiButtonVariant = TanukiButtonVariant.Filled,
    size: TanukiButtonSize = TanukiButtonSize.Medium,
    block: Boolean = false,
    enabled: Boolean = true
) {
    val height = size.height()
    val horizontalPadding = size.horizontalPadding()
    val colors = variant.colors()

    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .then(if (block) Modifier.fillMaxWidth() else Modifier)
                .height(height)
                .alpha(if (enabled) 1f else 0.4f)
                .clip(TanukiRadii.pillShape)
                .background(colors.background)
                .then(
                    colors.border?.let { Modifier.border(it, TanukiRadii.pillShape) } ?: Modifier
                ).clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = enabled,
                    onClick = onClick
                ).padding(horizontal = horizontalPadding)
    ) {
        Text(
            text = text,
            style = TanukiTheme.typography.label,
            color = colors.content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun TanukiButtonSize.height(): Dp =
    when (this) {
        TanukiButtonSize.Small -> 34.dp
        TanukiButtonSize.Medium -> TanukiSpacing.tapMin
        TanukiButtonSize.Large -> 52.dp
    }

private fun TanukiButtonSize.horizontalPadding(): Dp =
    when (this) {
        TanukiButtonSize.Small -> 14.dp
        TanukiButtonSize.Medium -> 20.dp
        TanukiButtonSize.Large -> 26.dp
    }

private data class ButtonColors(
    val background: Color,
    val content: Color,
    val border: BorderStroke?
)

@Composable
private fun TanukiButtonVariant.colors(): ButtonColors =
    when (this) {
        TanukiButtonVariant.Filled ->
            ButtonColors(TanukiTheme.colors.accent, MaterialTheme.colorScheme.onPrimary, null)
        TanukiButtonVariant.Tonal ->
            ButtonColors(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurface, null)
        TanukiButtonVariant.Text ->
            ButtonColors(Color.Transparent, TanukiTheme.colors.accent, null)
        TanukiButtonVariant.Outline ->
            ButtonColors(
                Color.Transparent,
                MaterialTheme.colorScheme.onSurface,
                BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            )
        TanukiButtonVariant.Danger ->
            // Destructive action: danger-tinted text on a hairline danger outline (never a loud filled
            // red), so it reads as reversible-but-careful rather than alarming.
            ButtonColors(
                Color.Transparent,
                TanukiTheme.colors.danger,
                BorderStroke(1.dp, TanukiTheme.colors.danger)
            )
    }

@Composable
@Preview
private fun TanukiButtonPreview() {
    TanukiTheme {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TanukiButton(text = "Reset", onClick = {}, variant = TanukiButtonVariant.Text)
            TanukiButton(text = "Show 24 titles", onClick = {}, variant = TanukiButtonVariant.Filled)
        }
    }
}