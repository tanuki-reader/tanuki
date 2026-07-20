package app.tanuki.assets.designSystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The Tanuki brand mark — a geometric tanuki (raccoon dog) face — drawn directly with [Canvas] rather
 * than loaded as an image. The mark is the one proprietary glyph in the design system (see CLAUDE.md →
 * Design System); reproducing its `assets/logo.svg` shapes as vector draw ops keeps it crisp at any
 * size, resource-pipeline-free, and identical on Android and iOS — and lets [TanukiLoadingIndicator]
 * animate it.
 *
 * Its palette is intrinsic to the brand (orange face, near-black mask, white eyes), so it does NOT
 * follow the theme; the mark reads the same in light and dark.
 *
 * @param size the square-edge length of the mark.
 */
@Composable
fun TanukiLogo(
    modifier: Modifier = Modifier,
    size: Dp = 96.dp
) {
    // Parsed from the exact `d` attributes of the brand logo.svg; cached so recomposition is cheap.
    val mask = remember { PathParser().parsePathString(MASK_PATH).toPath() }
    val nose = remember { PathParser().parsePathString(NOSE_PATH).toPath() }
    Canvas(modifier.size(size)) { drawTanuki(mask, nose) }
}

/** Draws the mark in the SVG's native 512×512 space, scaled to fill the current [DrawScope]. */
private fun DrawScope.drawTanuki(
    mask: Path,
    nose: Path
) {
    val factor = size.minDimension / VIEWPORT
    scale(factor, factor, pivot = Offset.Zero) {
        // Ears — rotated ellipses behind the head.
        rotate(-EAR_TILT, pivot = Offset(EAR_L_X, EAR_Y)) {
            drawOval(DARK, topLeft = Offset(EAR_L_X - EAR_RX, EAR_Y - EAR_RY), size = EAR_SIZE)
        }
        rotate(EAR_TILT, pivot = Offset(EAR_R_X, EAR_Y)) {
            drawOval(DARK, topLeft = Offset(EAR_R_X - EAR_RX, EAR_Y - EAR_RY), size = EAR_SIZE)
        }
        // Head.
        drawCircle(ORANGE, radius = HEAD_R, center = Offset(HEAD_CX, HEAD_CY))
        // Bandit mask.
        drawPath(mask, DARK)
        // Eyes.
        drawCircle(Color.White, radius = EYE_R, center = Offset(EYE_L_X, EYE_Y))
        drawCircle(Color.White, radius = EYE_R, center = Offset(EYE_R_X, EYE_Y))
        // Nose.
        drawPath(nose, DARK)
    }
}

private const val VIEWPORT = 512f

private val ORANGE = Color(0xFFE8731C)
private val DARK = Color(0xFF1A1A1A)

private const val EAR_TILT = 18f
private const val EAR_L_X = 150f
private const val EAR_R_X = 362f
private const val EAR_Y = 150f
private const val EAR_RX = 46f
private const val EAR_RY = 54f
private val EAR_SIZE = Size(EAR_RX * 2, EAR_RY * 2)

private const val HEAD_CX = 256f
private const val HEAD_CY = 268f
private const val HEAD_R = 156f

private const val EYE_L_X = 196f
private const val EYE_R_X = 316f
private const val EYE_Y = 246f
private const val EYE_R = 19f

private const val MASK_PATH =
    "M256 214 q-78 -26 -126 16 q-26 24 -14 60 q16 40 70 34 q44 -6 66 -34 l4 22 l4 -22 " +
        "q22 28 66 34 q54 6 70 -34 q12 -36 -14 -60 q-48 -42 -126 -16 z"
private const val NOSE_PATH = "M256 296 q20 0 20 17 q0 17 -20 22 q-20 -5 -20 -22 q0 -17 20 -17z"

@Composable
@Preview
private fun TanukiLogoPreview() {
    TanukiLogo()
}