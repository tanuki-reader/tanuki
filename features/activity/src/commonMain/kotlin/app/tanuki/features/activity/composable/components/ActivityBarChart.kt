package app.tanuki.features.activity.composable.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.tanuki.assets.designSystem.TanukiTheme

private val CHART_HEIGHT = 120.dp

/**
 * Bars — one per day (per month for a year), never a smoothed line: a spline invents in-between values.
 * Tapping a bar selects it and [selectionLabel] says what it holds ("Oct 6 · 18 chapters").
 *
 * @param counts the bars' values, oldest first.
 * @param axisLabels one per bar, `null` where no label fits.
 */
@Composable
internal fun ActivityBarChart(
    counts: List<Int>,
    axisLabels: List<String?>,
    selectionLabel: @Composable (index: Int) -> String,
    modifier: Modifier = Modifier
) {
    var selected by remember(counts) { mutableStateOf<Int?>(null) }
    val colors = TanukiTheme.colors
    val max = (counts.maxOrNull() ?: 0).coerceAtLeast(1)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(TanukiTheme.spacing.xs)) {
        Text(
            // An empty day (or one still to come) has nothing to say.
            text = selected?.takeIf { counts[it] > 0 }?.let { selectionLabel(it) } ?: " ",
            style = TanukiTheme.typography.caption,
            color = colors.textFaint
        )
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(CHART_HEIGHT)
                .pointerInput(counts) {
                    detectTapGestures { offset ->
                        val index = (offset.x / (size.width.toFloat() / counts.size)).toInt().coerceIn(0, counts.lastIndex)
                        selected = index.takeUnless { it == selected }
                    }
                }
        ) {
            if (counts.isEmpty()) return@Canvas
            val slot = size.width / counts.size
            val barWidth = slot * 0.6f
            counts.forEachIndexed { index, count ->
                val height = size.height * count / max
                val color = if (index == selected) colors.accent else colors.accentTint
                drawRoundRect(
                    color = color,
                    topLeft = Offset(index * slot + (slot - barWidth) / 2, size.height - height),
                    size = Size(barWidth, height),
                    cornerRadius = CornerRadius(barWidth / 4)
                )
            }
        }
        Row(Modifier.fillMaxWidth()) {
            axisLabels.forEach { label ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        text = label.orEmpty(),
                        style = TanukiTheme.typography.caption,
                        color = colors.textFaint,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
}
