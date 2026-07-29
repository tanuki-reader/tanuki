package app.tanuki.features.reader.composable.components

import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import app.tanuki.features.reader.viewmodel.ReaderItem
import kotlin.math.abs
import kotlin.math.sign
import kotlinx.coroutines.launch

/** Long-strip layout (webtoons): items stacked in a continuous scroll, no per-item snapping. Transition
 *  slides fill the viewport, so scrolling past a chapter's last page lands on a full "up next" panel and
 *  scrolling on flows into the following chapter.
 *
 *  Zoom is applied to the **whole strip**, not to individual pages: pinching scales the entire
 *  [LazyColumn] through one [graphicsLayer], so neighbouring pages grow with the one under the fingers and
 *  the strip stays continuous. (Zooming a single page in place would only enlarge that page and shove the
 *  ones after it out of alignment.) Because the layer is scaled rather than re-laid-out, the list still
 *  measures at viewport size and scrolls normally — Compose maps pointer positions through the layer, so a
 *  drag moves the content exactly under the finger at any zoom level.
 *
 *  While zoomed a drag moves the page **freely, in both axes at once**: the list scrolls it vertically and
 *  [detectStripPan] walks it sideways from the same unconsumed events, so a diagonal drag travels diagonally
 *  rather than being locked to whichever axis claimed the gesture first. */
@Composable
internal fun WebtoonReader(
    items: List<ReaderItem>,
    listState: LazyListState,
    ratios: PageAspectRatios,
    onToggleChrome: () -> Unit
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var viewportWidth by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    // The gesture modifiers are keyed on Unit — they must outlive recomposition to keep an in-flight
    // gesture — so the tap callback is read through a handle that recomposition can refresh.
    val toggleChrome by rememberUpdatedState(onToggleChrome)

    /** Keeps a horizontal translation within the scaled strip, so panning can never expose a margin
     *  beside the page. At rest ([at] == 1) the only legal position is flush left. */
    fun panX(
        x: Float,
        at: Float
    ) = x.coerceIn(minimumValue = viewportWidth * (1f - at), maximumValue = 0f)

    /**
     * Re-anchors the strip around [pivot] after a scale change of [zoom] and a two-finger drag of [pan],
     * so the content under the fingers stays under them. Horizontal movement is a layer translation
     * (clamped to the scaled content's edges); vertical movement is handed to the list as a scroll, since
     * the strip is unbounded downwards and only the list can travel through it.
     */
    fun transform(
        pivot: Offset,
        zoom: Float,
        pan: Offset
    ) {
        val from = scale
        val to = (from * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
        if (to == from && pan == Offset.Zero) return

        val moved = pivot + pan
        offsetX = panX(moved.x - (pivot.x - offsetX) * (to / from), to)
        listState.dispatchRawDelta(pivot.y / from - moved.y / to)
        scale = to
    }

    Box(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { viewportWidth = it.width.toFloat() }
            .pointerInput(Unit) { detectStripZoom(::transform) }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { toggleChrome() },
                    onDoubleTap = { tap ->
                        val target = if (scale > 1f) 1f else DOUBLE_TAP_SCALE
                        scope.launch {
                            var previous = scale
                            animate(initialValue = scale, targetValue = target) { value, _ ->
                                transform(tap, value / previous, Offset.Zero)
                                previous = value
                            }
                        }
                    }
                )
            }
            // Last in the chain, so it reads each event before the tap detector does and can mark a
            // finished pan as spent (see [detectStripPan]).
            .pointerInput(Unit) {
                detectStripPan(zoomed = { scale > 1f }) { dx -> offsetX = panX(offsetX + dx, scale) }
            }
    ) {
        // Each page is a whole-pixel-tall slot but its image fits at a fractional height, leaving a
        // sub-pixel band of background between pages — a faint seam. Overlapping the items by a hair lets
        // each page cover the seam above it, so the strip reads as one continuous image.
        LazyColumn(
            state = listState,
            modifier =
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offsetX
                        transformOrigin = TransformOrigin(0f, 0f)
                    },
            verticalArrangement = Arrangement.spacedBy((-1).dp)
        ) {
            items(items, key = { it.key }) { item ->
                ReaderItemContent(
                    item = item,
                    onToggleChrome = onToggleChrome,
                    fillHeight = false,
                    zoomable = false,
                    ratios = ratios,
                    transitionModifier = Modifier.fillParentMaxHeight().fillMaxWidth()
                )
            }
        }
    }
}

/**
 * Reports the horizontal travel of a one-finger drag while the strip is [zoomed], so the page can be walked
 * sideways.
 *
 * It deliberately **never consumes the drag**, which is what makes diagonal movement work: the `LazyColumn`
 * underneath reads the very same events and keeps scrolling vertically with its own physics — fling,
 * overscroll, item reporting — while this adds the horizontal component on top. Consuming (or letting a
 * built-in `detectHorizontalDragGestures` consume) would cancel the list's drag, which is why a gesture used
 * to resolve to one axis or the other and never both.
 *
 * Movement has to clear the touch slop before it pans, so the sideways jitter in a vertical flick doesn't
 * drift the page. Once a pan has happened the lift *is* consumed: this detector sits after the tap detector
 * in the modifier chain, so it sees the event first, and marking the finger-up as consumed is what stops the
 * end of a pan from registering as a tap on the page. Multi-touch is left alone entirely — [detectStripZoom]
 * owns pinches, including their pan.
 */
private suspend fun PointerInputScope.detectStripPan(
    zoomed: () -> Boolean,
    onPan: (Float) -> Unit
) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var travel = 0f
        var panning = false
        var pinched = false
        do {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            pinched = pinched || pressed.size > 1

            if (!pinched && zoomed()) {
                val dx = pressed.singleOrNull()?.positionChangeIgnoreConsumed()?.x ?: 0f
                if (panning) {
                    onPan(dx)
                } else {
                    travel += dx
                    if (abs(travel) > viewConfiguration.touchSlop) {
                        panning = true
                        onPan(travel - viewConfiguration.touchSlop * sign(travel))
                    }
                }
            }
            if (panning) event.changes.forEach { if (it.changedToUp()) it.consume() }
        } while (event.changes.any { it.pressed })
    }
}

/**
 * Reports pinch gestures as (centroid, zoom, pan) triples. Only multi-touch is claimed: a single finger is
 * left entirely alone so the [LazyColumn] scrolls as usual, and once a second finger lands the changes are
 * consumed on the [PointerEventPass.Initial] pass so the list stops treating the pinch as a drag.
 */
private suspend fun PointerInputScope.detectStripZoom(
    onTransform: (pivot: Offset, zoom: Float, pan: Offset) -> Unit
) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var pressed: Boolean
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.count { it.pressed } > 1) {
                val zoom = event.calculateZoom()
                val pan = event.calculatePan()
                val pivot = event.calculateCentroid(useCurrent = false)
                if (pivot.isSpecified && (zoom != 1f || pan != Offset.Zero)) {
                    onTransform(pivot, zoom, pan)
                }
                event.changes.forEach { it.consume() }
            }
            pressed = event.changes.any { it.pressed }
        } while (pressed)
    }
}

/** Zoom bounds for the strip: never below the fitted width, and capped where pages stay legible rather
 *  than turning into decode artefacts. [DOUBLE_TAP_SCALE] is the step a double-tap toggles to. */
private const val MIN_SCALE = 1f
private const val MAX_SCALE = 4f
private const val DOUBLE_TAP_SCALE = 2f