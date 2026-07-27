package app.tanuki.features.reader.composable.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import app.tanuki.assets.designSystem.TanukiTheme
import app.tanuki.assets.designSystem.component.ChevronRightIcon
import app.tanuki.assets.designSystem.component.TanukiIconButton
import app.tanuki.features.reader.resources.Res
import app.tanuki.features.reader.resources.reader_page_indicator
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/**
 * The bottom-chrome page control: a page count over a `[prev chapter] — slider — [next chapter]` row. The
 * page count and slider track the chapter the visible page belongs to, so they update as the reader flows
 * across boundaries. The prev/next buttons jump to the start of the adjacent chapter (loading it first if
 * needed).
 *
 * Dragging the slider jumps to any page. While dragging it follows the finger and the label previews the
 * target page ([scrubValue]); the actual scroll fires once on release, so the pager/list isn't churned
 * every frame. The slider is continuous (no tick marks — a long chapter would be a picket fence) and its
 * value is rounded to a page. In right-to-left mode it is mirrored (page 1 on the right, filling leftward)
 * so its direction matches the reading direction.
 */
@Composable
internal fun ColumnScope.PageScrubber(
    currentPage: Int,
    pageCount: Int,
    reversed: Boolean,
    hasPreviousChapter: Boolean,
    hasNextChapter: Boolean,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onScrubToPage: (Int) -> Unit
) {
    // Non-null only while a drag is in progress; drives both the slider position and the label preview.
    var scrubValue by remember { mutableStateOf<Float?>(null) }
    val displayPage = (scrubValue?.roundToInt() ?: currentPage).coerceIn(0, (pageCount - 1).coerceAtLeast(0))

    Text(
        text = stringResource(Res.string.reader_page_indicator, displayPage + 1, pageCount),
        style = TanukiTheme.typography.caption,
        color = Color.White,
        modifier = Modifier.align(Alignment.CenterHorizontally)
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TanukiTheme.spacing.xs)
    ) {
        TanukiIconButton(
            onClick = onPreviousChapter,
            icon = { tint ->
                ChevronRightIcon(
                    tint = if (hasPreviousChapter) tint else TanukiTheme.colors.textFaint,
                    modifier = Modifier.rotate(180f)
                )
            }
        )
        // A slider needs at least two pages to have anywhere to travel; a one-page chapter just spaces.
        if (pageCount > 1) {
            Box(Modifier.weight(1f)) {
                val slider: @Composable () -> Unit = {
                    Slider(
                        value = (scrubValue ?: currentPage.toFloat()).coerceIn(0f, (pageCount - 1).toFloat()),
                        onValueChange = { scrubValue = it },
                        onValueChangeFinished = {
                            scrubValue?.let { onScrubToPage(it.roundToInt().coerceIn(0, pageCount - 1)) }
                            scrubValue = null
                        },
                        valueRange = 0f..(pageCount - 1).toFloat(),
                        colors =
                            SliderDefaults.colors(
                                thumbColor = TanukiTheme.colors.accent,
                                activeTrackColor = TanukiTheme.colors.accent,
                                inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                            ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (reversed) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { slider() }
                } else {
                    slider()
                }
            }
        } else {
            Spacer(Modifier.weight(1f))
        }
        TanukiIconButton(
            onClick = onNextChapter,
            icon = { tint ->
                ChevronRightIcon(tint = if (hasNextChapter) tint else TanukiTheme.colors.textFaint)
            }
        )
    }
}