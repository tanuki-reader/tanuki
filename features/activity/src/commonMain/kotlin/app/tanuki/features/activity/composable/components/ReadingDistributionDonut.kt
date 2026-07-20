package app.tanuki.features.activity.composable.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tanuki.assets.designSystem.TanukiTheme

private val DONUT_SIZE = 132.dp
private val RING_WIDTH = 22.dp
private val SWATCH = 10.dp
private const val MAX_SLICES = 5

/** One labeled quantity to plot — a series title, a category name, etc. — folded and colored by the donut. */
internal data class DistributionEntry(
    val label: String,
    val count: Int
)

/** A distribution slice ready to draw: its [label] (an entry or the "other" bucket), [count], [color]. */
private data class Slice(
    val label: String,
    val count: Int,
    val color: Color
)

/**
 * An all-time distribution donut: how a total splits across its top [entries], with the long tail folded
 * into an "other" bucket. Drawn with [Canvas] (no charting dependency), paired with a legend and per-slice
 * share. Generic over what the entries are — the same component charts reads-per-series and reads-per-category.
 *
 * @param entries labeled counts, most-first (e.g. series or categories); order sets slice priority.
 * @param otherLabel resolved UI copy for the folded-tail bucket (e.g. "Other").
 */
@Composable
internal fun ReadingDistributionDonut(
    entries: List<DistributionEntry>,
    otherLabel: String,
    modifier: Modifier = Modifier
) {
    val palette = distributionPalette()
    val total = entries.sumOf { it.count }
    if (total == 0) return

    // Top entries keep their own slice; everything past MAX_SLICES folds into one "other" slice.
    val slices =
        buildList {
            entries.take(MAX_SLICES).forEachIndexed { index, entry ->
                add(Slice(entry.label, entry.count, palette[index % palette.size]))
            }
            val otherCount = entries.drop(MAX_SLICES).sumOf { it.count }
            if (otherCount > 0) add(Slice(otherLabel, otherCount, TanukiTheme.colors.textFaint))
        }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(TanukiTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically
    ) {
        DonutRing(slices = slices, total = total)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(TanukiTheme.spacing.sm)
        ) {
            slices.forEach { slice ->
                LegendRow(slice = slice, percent = slice.count * 100 / total)
            }
        }
    }
}

@Composable
private fun DonutRing(
    slices: List<Slice>,
    total: Int
) {
    val ring = with(LocalDensity.current) { RING_WIDTH.toPx() }
    Canvas(Modifier.size(DONUT_SIZE)) {
        val stroke = Stroke(width = ring, cap = StrokeCap.Butt)
        val inset = ring / 2f
        val arcSize = Size(size.width - ring, size.height - ring)
        val topLeft = Offset(inset, inset)
        var startAngle = -90f // start at 12 o'clock
        slices.forEach { slice ->
            val sweep = slice.count.toFloat() / total * 360f
            drawArc(
                color = slice.color,
                startAngle = startAngle,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke
            )
            startAngle += sweep
        }
    }
}

@Composable
private fun LegendRow(
    slice: Slice,
    percent: Int
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(TanukiTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(SWATCH)
                .clip(CircleShape)
                .background(slice.color)
        )
        Text(
            text = slice.label,
            style = TanukiTheme.typography.body,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "$percent%",
            style = TanukiTheme.typography.caption,
            color = TanukiTheme.colors.textFaint
        )
    }
}

/** On-brand, visually distinct slice colors — the accent leads, then the status/info hues. */
@Composable
private fun distributionPalette(): List<Color> {
    val colors = TanukiTheme.colors
    return listOf(colors.accent, colors.info, colors.success, colors.warning, colors.accentHover)
}