package app.tanuki.features.activity.composable.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import app.tanuki.assets.designSystem.TanukiTheme
import app.tanuki.assets.designSystem.component.LibraryTabIcon
import app.tanuki.assets.designSystem.component.TrashIcon
import app.tanuki.features.activity.viewmodel.HistoryRow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val SLOT_WIDTH = 68.dp
private val BOBBLE_SIZE = 46.dp
private val END_PADDING = 8.dp

/**
 * A [HistoryRow] with an iOS-style swipe-to-reveal: dragging the row left uncovers two round action
 * "bobbles" floating on the page — **series** (open the manga's detail) and **delete** (remove the entry).
 * A plain tap still opens the chapter ([onOpenChapter]); while the panel is open, a tap closes it.
 *
 * The bobbles scale and fade in with the swipe progress (they "pop"), rather than sitting as flat blocks,
 * so a partial swipe reads as a peek. Built on the stable `draggable` + `Animatable` primitives, snapping
 * open/closed past the halfway point so a row never gets stuck mid-swipe.
 *
 * [seriesLabel]/[deleteLabel] are resolved UI copy.
 */
@Composable
internal fun SwipeableHistoryRow(
    row: HistoryRow,
    onOpenChapter: () -> Unit,
    onOpenSeries: () -> Unit,
    onDelete: () -> Unit,
    seriesLabel: String,
    deleteLabel: String,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val revealPx = with(LocalDensity.current) { (SLOT_WIDTH * 2 + END_PADDING).toPx() }
    val offsetX = remember { Animatable(0f) }
    val isOpen = offsetX.value != 0f
    // 0 when closed → 1 when fully revealed; drives the bobbles' pop-in.
    fun progress() = (-offsetX.value / revealPx).coerceIn(0f, 1f)

    fun close() = scope.launch { offsetX.animateTo(0f) }

    Box(modifier.fillMaxWidth()) {
        // Action bobbles, behind the row and pinned to the end, revealed as the row slides left.
        Row(
            modifier = Modifier.matchParentSize().padding(end = END_PADDING),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ActionBobble(
                label = seriesLabel,
                container = MaterialTheme.colorScheme.surfaceVariant,
                content = MaterialTheme.colorScheme.onSurfaceVariant,
                icon = { tint -> LibraryTabIcon(tint = tint) },
                progress = ::progress,
                onClick = {
                    close()
                    onOpenSeries()
                }
            )
            ActionBobble(
                label = deleteLabel,
                container = TanukiTheme.colors.danger,
                content = Color.White,
                icon = { tint -> TrashIcon(tint = tint) },
                progress = ::progress,
                // No close(): deleting removes the row from the list, so there's nothing left to snap back.
                onClick = onDelete
            )
        }

        // Foreground row — opaque, so it fully covers the bobbles when closed — offset by the drag.
        Box(
            Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .draggable(
                    orientation = Orientation.Horizontal,
                    state =
                        rememberDraggableState { delta ->
                            scope.launch { offsetX.snapTo((offsetX.value + delta).coerceIn(-revealPx, 0f)) }
                        },
                    onDragStopped = {
                        val target = if (offsetX.value <= -revealPx / 2f) -revealPx else 0f
                        scope.launch { offsetX.animateTo(target) }
                    }
                )
        ) {
            HistoryRowItem(
                row = row,
                onClick = { if (isOpen) close() else onOpenChapter() }
            )
        }
    }
}

/** One round action button (icon in a colored circle) over a caption, popping in with the swipe [progress]. */
@Composable
private fun ActionBobble(
    label: String,
    container: Color,
    content: Color,
    icon: @Composable (tint: Color) -> Unit,
    progress: () -> Float,
    onClick: () -> Unit
) {
    Column(
        modifier =
            Modifier
                .width(SLOT_WIDTH)
                .graphicsLayer {
                    val p = progress()
                    alpha = p
                    val scale = lerp(0.5f, 1f, p)
                    scaleX = scale
                    scaleY = scale
                },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(TanukiTheme.spacing.xxs)
    ) {
        Box(
            modifier =
                Modifier
                    .size(BOBBLE_SIZE)
                    .clip(CircleShape)
                    .background(container)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClick
                    ),
            contentAlignment = Alignment.Center
        ) {
            icon(content)
        }
        Text(
            text = label,
            style = TanukiTheme.typography.caption,
            color = TanukiTheme.colors.textFaint,
            textAlign = TextAlign.Center
        )
    }
}
