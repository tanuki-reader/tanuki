package app.tanuki.features.activity.composable.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tanuki.assets.designSystem.TanukiTheme
import app.tanuki.core.domain.history.model.ReadingActivityPoint

private val CHART_HEIGHT = 160.dp
private val LINE_WIDTH = 2.dp
private val VERTICAL_INSET = 12.dp
private val LABEL_AREA = 20.dp
private val LABEL_GAP = 6.dp

/**
 * A minimal, smoothly-curved area + line chart of the reading curve — chapters read per bucket, drawn with
 * [Canvas] so it needs no charting dependency (the same resource-free approach as the design system's
 * icons). The line rounds through each point via a Catmull-Rom spline rather than kinking at sharp
 * corners; the brand accent draws the line over a soft accent wash for the fill.
 *
 * Trend-over-time is the right form here (per the design-intelligence chart guidance): a continuous,
 * gap-filled series over a period. The series is expected pre-bucketed and gap-filled by
 * [app.tanuki.core.domain.history.usecase.GetReadingActivity], so this only maps values to pixels.
 *
 * @param points the gap-filled series, oldest first; a baseline is drawn when it's empty or all-zero.
 * @param labels one entry per point — a non-null string is drawn as an x-axis tick under that point (e.g.
 *   a weekday initial, a day number, a month abbreviation); `null` draws no tick. Empty = no axis.
 */
@Composable
internal fun ReadingCurveChart(
    points: List<ReadingActivityPoint>,
    modifier: Modifier = Modifier,
    labels: List<String?> = emptyList(),
    height: Dp = CHART_HEIGHT
) {
    val accent = TanukiTheme.colors.accent
    val baselineColor = TanukiTheme.colors.textFaint
    val labelColor = TanukiTheme.colors.textFaint
    val labelStyle = TanukiTheme.typography.caption.copy(color = labelColor)
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val lineStroke = with(density) { LINE_WIDTH.toPx() }
    val inset = with(density) { VERTICAL_INSET.toPx() }
    val hasLabels = labels.any { it != null }
    val labelArea = with(density) { if (hasLabels) LABEL_AREA.toPx() else 0f }
    val labelGap = with(density) { LABEL_GAP.toPx() }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
    ) {
        val maxCount = (points.maxOfOrNull { it.count } ?: 0).coerceAtLeast(1)
        val baselineY = size.height - inset - labelArea
        val plotHeight = baselineY - inset

        // Baseline the curve sits on.
        drawLine(
            color = baselineColor.copy(alpha = 0.4f),
            start = Offset(0f, baselineY),
            end = Offset(size.width, baselineY),
            strokeWidth = 1f
        )

        if (points.isEmpty()) return@Canvas

        // x for point i; a single point sits centered so it still draws a flat line across the width.
        fun xAt(index: Int): Float =
            if (points.size == 1) size.width / 2f else size.width * index / (points.size - 1)

        fun yAt(count: Int): Float = baselineY - (count.toFloat() / maxCount) * plotHeight

        val pts = points.mapIndexed { index, point -> Offset(xAt(index), yAt(point.count)) }

        // Smoothly connect the points with a Catmull-Rom spline expressed as cubic béziers, so the curve
        // rounds through each reading instead of kinking at sharp corners. Control points are clamped to
        // the plot band [inset, baselineY] so the rounding never overshoots past the top or the baseline.
        fun appendSmooth(path: Path) {
            for (i in 0 until pts.lastIndex) {
                val p0 = pts[(i - 1).coerceAtLeast(0)]
                val p1 = pts[i]
                val p2 = pts[i + 1]
                val p3 = pts[(i + 2).coerceAtMost(pts.lastIndex)]
                val c1y = (p1.y + (p2.y - p0.y) / 6f).coerceIn(inset, baselineY)
                val c2y = (p2.y - (p3.y - p1.y) / 6f).coerceIn(inset, baselineY)
                path.cubicTo(
                    p1.x + (p2.x - p0.x) / 6f, c1y,
                    p2.x - (p3.x - p1.x) / 6f, c2y,
                    p2.x, p2.y
                )
            }
        }

        val linePath = Path()
        val areaPath = Path()
        if (pts.size == 1) {
            // A lone bucket has no span to curve across — draw it as a flat line the full width.
            val y = pts[0].y
            linePath.moveTo(0f, y)
            linePath.lineTo(size.width, y)
            areaPath.moveTo(0f, baselineY)
            areaPath.lineTo(0f, y)
            areaPath.lineTo(size.width, y)
        } else {
            linePath.moveTo(pts[0].x, pts[0].y)
            appendSmooth(linePath)
            areaPath.moveTo(pts[0].x, baselineY)
            areaPath.lineTo(pts[0].x, pts[0].y)
            appendSmooth(areaPath)
        }
        // Close the area down to the baseline at the right edge. (For a single point pts.last().x is the
        // centre, not the edge, so use the full width explicitly to avoid a triangular fill.)
        areaPath.lineTo(size.width, baselineY)
        areaPath.close()

        drawPath(areaPath, color = accent.copy(alpha = 0.16f))
        drawPath(
            path = linePath,
            color = accent,
            style = Stroke(width = lineStroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // X-axis tick labels, centered under their point and clamped inside the width.
        if (hasLabels) drawAxisLabels(labels, ::xAt, baselineY + labelGap, textMeasurer, labelStyle)
    }
}

/** Draws each non-null [labels] entry centered under its point's x, clamped within the canvas width. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawAxisLabels(
    labels: List<String?>,
    xAt: (Int) -> Float,
    top: Float,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    style: TextStyle
) {
    labels.forEachIndexed { index, label ->
        if (label == null) return@forEachIndexed
        val measured = textMeasurer.measure(label, style)
        val x = (xAt(index) - measured.size.width / 2f).coerceIn(0f, size.width - measured.size.width)
        drawText(measured, topLeft = Offset(x, top))
    }
}