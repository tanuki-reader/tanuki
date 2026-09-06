package app.tanuki.assets.designSystem.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tanuki.assets.designSystem.TanukiMotion
import app.tanuki.assets.designSystem.TanukiTheme

/**
 * A small **determinate** progress ring: a faint full-circle track with an accent arc drawn over the
 * fraction that is done. Its home is the chapter row's download affordance, where it has to say "this one
 * is 7 pages into 20" in the space a glyph occupies.
 *
 * Distinct from [TanukiLoadingIndicator], which is the branded, logo-bearing, *indeterminate* mark for a
 * screen or section that is waiting. This is a row-scale status glyph: no logo, no brand moment, and it
 * carries a number the user is watching climb.
 *
 * ### Unknown progress is a state, not a zero
 *
 * [progress] is nullable because the thing it reports genuinely may not be known yet — a queued chapter's
 * page count is only discovered when the download reaches it, since some sources hand out image URLs that
 * expire (see `docs/design-downloads.md` §5). A `null` therefore sweeps a short arc around the ring
 * instead of drawing an empty one: a ring pinned at 0% and a ring that does not know are the same picture
 * otherwise, and the first is a lie that reads as "stuck".
 *
 * The determinate arc is animated between values rather than jumping, because progress arrives one page
 * at a time and an un-eased step reads as a flicker on a 20dp glyph.
 *
 * @param progress how much is done, `0f..1f`, or `null` when it is not yet known.
 * @param size the ring's outer diameter. Keep the caller's tap target at
 *   [app.tanuki.assets.designSystem.TanukiSpacing.tapMin] regardless of this.
 * @param color the arc colour; the accent by default.
 * @param trackColor the unfilled remainder.
 */
@Composable
fun TanukiProgressRing(
    progress: Float?,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    color: Color = TanukiTheme.colors.accent,
    trackColor: Color = TanukiTheme.colors.scrimSoft
) {
    // Kept above the branch so switching between unknown and known progress doesn't restart the
    // composition's animation state — a chapter crosses that boundary exactly once, mid-download.
    val transition = rememberInfiniteTransition(label = "progress-ring")
    val sweepStart by transition.animateFloat(
        initialValue = 0f,
        targetValue = FULL_CIRCLE,
        animationSpec = infiniteRepeatable(tween(INDETERMINATE_MILLIS, easing = LinearEasing), RepeatMode.Restart),
        label = "sweep"
    )
    val fraction by animateFloatAsState(
        targetValue = progress?.coerceIn(0f, 1f) ?: 0f,
        animationSpec = tween(TanukiMotion.reducedMillis, easing = LinearEasing),
        label = "fraction"
    )

    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val strokeWidth = this.size.minDimension * RING_STROKE_FRACTION
            val inset = strokeWidth / 2
            val topLeft = Offset(inset, inset)
            val arcSize = Size(this.size.width - strokeWidth, this.size.height - strokeWidth)
            val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)

            drawArc(trackColor, 0f, FULL_CIRCLE, useCenter = false, topLeft = topLeft, size = arcSize, style = stroke)
            if (progress == null) {
                drawArc(color, sweepStart, SWEEP_DEGREES, useCenter = false, topLeft, arcSize, style = stroke)
            } else {
                // From twelve o'clock, clockwise — the direction a progress ring is read everywhere.
                drawArc(color, START_ANGLE, FULL_CIRCLE * fraction, false, topLeft, arcSize, style = stroke)
            }
        }
    }
}

/** Thicker than [TanukiLoadingIndicator]'s ring: this one is a tenth of the diameter and must still read. */
private const val RING_STROKE_FRACTION = 0.13f

/** Twelve o'clock, in the degrees-clockwise-from-three-o'clock space `drawArc` uses. */
private const val START_ANGLE = -90f
private const val FULL_CIRCLE = 360f

/** The arc an unknown-progress ring sweeps. Short enough to read as motion rather than as a value. */
private const val SWEEP_DEGREES = 80f

/** One full turn while progress is unknown. Slow enough not to draw the eye off the list. */
private const val INDETERMINATE_MILLIS = 1100

@Composable
@Preview
private fun TanukiProgressRingPreview() {
    TanukiTheme {
        Box(Modifier.size(60.dp), contentAlignment = Alignment.Center) {
            TanukiProgressRing(progress = 0.35f)
        }
    }
}