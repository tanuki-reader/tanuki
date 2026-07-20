package app.tanuki.assets.designSystem.component

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tanuki.assets.designSystem.TanukiTheme

/**
 * The app's branded indeterminate loading indicator: the [TanukiLogo] "breathing" at the centre of a
 * rotating accent sweep. Use it anywhere a screen or section is waiting on data — it replaces a plain
 * `CircularProgressIndicator` with the brand mark, so loading feels like Tanuki rather than stock
 * Material. Lives in the design system because every feature reuses it.
 *
 * Sizes itself to [size] and centres its content; wrap it in a `Box(fillMaxSize)` to centre it within a
 * larger area. The animation runs for as long as the composable is on screen.
 *
 * @param size the overall diameter of the indicator (ring included).
 */
@Composable
fun TanukiLoadingIndicator(
    modifier: Modifier = Modifier,
    size: Dp = 72.dp
) {
    val transition = rememberInfiniteTransition(label = "tanuki-loading")
    val breathe by transition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathe"
    )
    val sweepStart by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart),
        label = "sweep"
    )

    val accent = TanukiTheme.colors.accent
    val track = TanukiTheme.colors.scrimSoft

    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val strokeWidth = this.size.minDimension * RING_STROKE_FRACTION
            val inset = strokeWidth / 2
            val topLeft = Offset(inset, inset)
            val arcSize = Size(this.size.width - strokeWidth, this.size.height - strokeWidth)
            val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            // Faint full-circle track, then the accent arc sweeping around it.
            drawArc(track, 0f, FULL_CIRCLE, useCenter = false, topLeft = topLeft, size = arcSize, style = stroke)
            drawArc(accent, sweepStart, SWEEP_DEGREES, useCenter = false, topLeft = topLeft, size = arcSize, style = stroke)
        }
        TanukiLogo(
            modifier = Modifier.graphicsLayer { scaleX = breathe; scaleY = breathe },
            size = size * LOGO_FRACTION
        )
    }
}

private const val RING_STROKE_FRACTION = 0.055f
private const val LOGO_FRACTION = 0.62f
private const val FULL_CIRCLE = 360f
private const val SWEEP_DEGREES = 90f

@Composable
@Preview
private fun TanukiLoadingIndicatorPreview() {
    TanukiTheme {
        Box(Modifier.size(160.dp), contentAlignment = Alignment.Center) {
            TanukiLoadingIndicator()
        }
    }
}
