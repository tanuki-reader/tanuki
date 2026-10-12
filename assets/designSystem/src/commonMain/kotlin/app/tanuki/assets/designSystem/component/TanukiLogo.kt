package app.tanuki.assets.designSystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The Tanuki mark: a persimmon leaf tilted 32°, split down its midrib into a lit half (`kaki`) and a
 * shaded half (`kaki-deep`), like a book lying open. Paths verbatim from the design system's
 * `tanuki-mark.svg` (`assets/logo.svg`), drawn in its 64-unit space and cropped to the SVG's viewBox.
 *
 * The two-tone mark is never themed. Pass [tint] for the single-ink (`mono`) variant: empty states in
 * `ink-faint`, watermarks.
 *
 * @param size the square-edge length of the mark. Keep at least 25% clear space around it.
 */
@Composable
fun TanukiLogo(
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
    tint: Color? = null,
) {
    val lit = remember { PathParser().parsePathString(LIT_PATH).toPath() }
    val shade = remember { PathParser().parsePathString(SHADE_PATH).toPath() }
    Canvas(modifier.size(size)) {
        val factor = this.size.minDimension / VIEW_SIZE
        scale(factor, factor, pivot = Offset.Zero) {
            translate(-VIEW_X, -VIEW_Y) {
                rotate(TILT, pivot = Offset(PIVOT, PIVOT)) {
                    drawPath(lit, tint ?: KAKI)
                    drawPath(shade, tint ?: KAKI_DEEP)
                }
            }
        }
    }
}

// Brand constants (`kaki`, `kaki-deep`): the mark's own colours, theme-invariant by design.
private val KAKI = Color(0xFFF2843A)
private val KAKI_DEEP = Color(0xFFB84E16)

private const val VIEW_X = 5.07f
private const val VIEW_Y = 5.49f
private const val VIEW_SIZE = 51.66f
private const val TILT = 32f
private const val PIVOT = 32f

private const val LIT_PATH = "M32 3 C 20 12 13.5 24 14 36 C 14.5 47 21 55 29.8 57.5 C 27.6 40 28.4 20 32 3 Z"
private const val SHADE_PATH =
    "M32.6 6.5 C 33.4 22 33.6 40 32.4 57.6 C 42 55.5 49.4 47.5 50 36 C 50.5 24.5 45 13.5 32.6 6.5 Z"

@Composable
@Preview
private fun TanukiLogoPreview() {
    TanukiLogo()
}
