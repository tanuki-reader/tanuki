package app.tanuki.assets.designSystem.component

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.tanuki.assets.designSystem.TanukiRadii
import app.tanuki.assets.designSystem.TanukiTheme

/** One option in a [TanukiSegmentedControl]. [label] is UI copy — pass a resolved localized string. */
data class Segment<T>(
    val value: T,
    val label: String
)

/**
 * Pick one of 2–4 short options — a sliding orange thumb on a `surfaceVariant` track (ported from the
 * iOS `TanukiSegmentedControl`, Material dialect). The thumb animates to the selected segment on any
 * change to [selected], including changes driven by an async source (a ViewModel `StateFlow`).
 */
@Composable
fun <T> TanukiSegmentedControl(
    segments: List<Segment<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    val inset = TanukiTheme.spacing.xxs
    val density = LocalDensity.current
    val selectedIndex = segments.indexOfFirst { it.value == selected }.coerceAtLeast(0)
    val count = segments.size.coerceAtLeast(1)

    // Read the latest segments/onSelect inside the (stably keyed) gesture without restarting it. Keying
    // pointerInput on `segments` — which callers usually build inline, so it's a fresh instance every
    // recomposition — would tear the gesture down and cancel the drag the moment onSelect recomposes.
    val currentSegments by rememberUpdatedState(segments)
    val currentOnSelect by rememberUpdatedState(onSelect)

    // Finger x within the track while a slide is in progress, or null when idle. Drives the thumb
    // directly during a drag so it tracks the finger; on release we fall back to the animated position.
    var dragX by remember { mutableStateOf<Float?>(null) }

    BoxWithConstraints(
        modifier =
            modifier
                .clip(TanukiRadii.containerShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(BOX_BORDER_WIDTH, MaterialTheme.colorScheme.outlineVariant, TanukiRadii.containerShape)
                .padding(inset)
                .pointerInput(count) {
                    // Segment under x, in a track `size.width` px wide split into `count` equal segments.
                    fun indexAt(x: Float): Int = (x / (size.width.toFloat() / count)).toInt().coerceIn(0, count - 1)

                    var lastIndex = -1
                    detectHorizontalDragGestures(
                        onDragStart = { start ->
                            dragX = start.x
                            lastIndex = indexAt(start.x)
                            currentOnSelect(currentSegments[lastIndex].value)
                        },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            dragX = change.position.x
                            val idx = indexAt(change.position.x)
                            if (idx != lastIndex) {
                                lastIndex = idx
                                currentOnSelect(currentSegments[idx].value)
                            }
                        },
                        onDragEnd = {
                            // Commit the final segment definitively, then hand the thumb back to the animation.
                            if (lastIndex in currentSegments.indices) {
                                currentOnSelect(currentSegments[lastIndex].value)
                            }
                            dragX = null
                        },
                        onDragCancel = { dragX = null }
                    )
                }
    ) {
        val segmentWidth = maxWidth / count
        val animatedOffset by animateDpAsState(segmentWidth * selectedIndex, label = "segmentedThumb")

        // While dragging, center the thumb on the finger, clamped inside the track; otherwise use the
        // animated (click- or state-driven) position.
        val thumbOffset =
            dragX?.let { x ->
                val segmentWidthPx = with(density) { segmentWidth.toPx() }
                val trackWidthPx = with(density) { maxWidth.toPx() }
                val leftPx = (x - segmentWidthPx / 2f).coerceIn(0f, trackWidthPx - segmentWidthPx)
                with(density) { leftPx.toDp() }
            } ?: animatedOffset

        // Sliding thumb, drawn under the labels. Its radius is the container radius inset by the track padding.
        Box(
            Modifier
                .offset(x = thumbOffset)
                .width(segmentWidth)
                .height(SEGMENT_HEIGHT)
                .clip(RoundedCornerShape(TanukiRadii.container - inset))
                .background(TanukiTheme.colors.accent)
        )

        Row(Modifier.fillMaxWidth()) {
            segments.forEach { seg ->
                val on = seg.value == selected
                Box(
                    contentAlignment = Alignment.Center,
                    modifier =
                        Modifier
                            .weight(1f)
                            .height(SEGMENT_HEIGHT)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { onSelect(seg.value) }
                ) {
                    Text(
                        text = seg.label,
                        style = TanukiTheme.typography.body.copy(fontWeight = FontWeight.Medium),
                        color =
                            if (on) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                    )
                }
            }
        }
    }
}

private val SEGMENT_HEIGHT = 34.dp

private val BOX_BORDER_WIDTH = 1.dp

@Composable
@Preview
private fun SegmentedControlPreview() {
    TanukiSegmentedControl(
        segments =
            listOf(
                Segment("1", "One"),
                Segment("2", "Two"),
                Segment("3", "Three"),
                Segment("4", "Four")
            ),
        selected = "2",
        onSelect = {}
    )
}