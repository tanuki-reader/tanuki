package app.tanuki.assets.designSystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The handful of Material Symbols Rounded glyphs the design system needs, reproduced as [Canvas] draw
 * ops in a 24-unit space — the same resource-pipeline-free approach as [TanukiLogo]. Material Symbols
 * aren't bundled as a font yet (see CLAUDE.md → Design System), and these are simple enough to
 * draw geometrically; swap them for the real font when it lands. Each takes a [tint] so the caller
 * controls color from theme tokens, and draws crisply at any [size], identical on Android and iOS.
 */

private const val ICON_VIEWPORT = 24f
private const val ICON_STROKE = 2f

/** `search` — a magnifying glass: lens circle plus a rounded handle. */
@Composable
internal fun SearchIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp
) {
    Canvas(modifier.size(size)) {
        iconSpace {
            drawCircle(
                color = tint,
                radius = 6.5f,
                center = Offset(10f, 10f),
                style = Stroke(width = ICON_STROKE)
            )
            drawLine(
                color = tint,
                start = Offset(14.6f, 14.6f),
                end = Offset(20f, 20f),
                strokeWidth = ICON_STROKE,
                cap = StrokeCap.Round
            )
        }
    }
}

/** `arrow_back` — a left-pointing arrow: a horizontal rule with a chevron head, to exit search. */
@Composable
internal fun BackIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp
) {
    Canvas(modifier.size(size)) {
        iconSpace {
            drawLine(tint, Offset(4.5f, 12f), Offset(19.5f, 12f), ICON_STROKE, StrokeCap.Round)
            drawLine(tint, Offset(4.5f, 12f), Offset(10.5f, 6f), ICON_STROKE, StrokeCap.Round)
            drawLine(tint, Offset(4.5f, 12f), Offset(10.5f, 18f), ICON_STROKE, StrokeCap.Round)
        }
    }
}

/** `close` — a rounded X, used to clear the search field. */
@Composable
internal fun CloseIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp
) {
    Canvas(modifier.size(size)) {
        iconSpace {
            drawLine(tint, Offset(6.5f, 6.5f), Offset(17.5f, 17.5f), ICON_STROKE, StrokeCap.Round)
            drawLine(tint, Offset(17.5f, 6.5f), Offset(6.5f, 17.5f), ICON_STROKE, StrokeCap.Round)
        }
    }
}

/** `filter_list` — three centered rules of decreasing width. */
@Composable
internal fun FilterIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp
) {
    Canvas(modifier.size(size)) {
        iconSpace {
            drawLine(tint, Offset(4f, 7f), Offset(20f, 7f), ICON_STROKE, StrokeCap.Round)
            drawLine(tint, Offset(7f, 12f), Offset(17f, 12f), ICON_STROKE, StrokeCap.Round)
            drawLine(tint, Offset(10f, 17f), Offset(14f, 17f), ICON_STROKE, StrokeCap.Round)
        }
    }
}

/**
 * `menu_book` — an open book: two mirrored page leaves meeting at a center spine. Sized for empty-state
 * illustrations (larger than the app-bar glyphs), it signals an empty or unmatched library.
 */
@Composable
fun EmptyLibraryIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp
) {
    Canvas(modifier.size(size)) {
        iconSpace {
            val leaf = Stroke(width = ICON_STROKE, cap = StrokeCap.Round, join = StrokeJoin.Round)
            val leftPage =
                Path().apply {
                    moveTo(12f, 7f)
                    lineTo(4f, 6f)
                    lineTo(4f, 17f)
                    lineTo(12f, 18f)
                }
            val rightPage =
                Path().apply {
                    moveTo(12f, 7f)
                    lineTo(20f, 6f)
                    lineTo(20f, 17f)
                    lineTo(12f, 18f)
                }
            drawPath(leftPage, tint, style = leaf)
            drawPath(rightPage, tint, style = leaf)
            drawLine(tint, Offset(12f, 7f), Offset(12f, 18f), ICON_STROKE, StrokeCap.Round)
        }
    }
}

/**
 * `arrow_upward` — a vertical shaft with a chevron head at the top. Marks an **ascending** sort in the
 * library sort list; pairs with [ArrowDownwardIcon] for descending. Public (unlike the app-bar glyphs
 * above) so feature modules can render it in row controls.
 */
@Composable
fun ArrowUpwardIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp
) {
    Canvas(modifier.size(size)) {
        iconSpace {
            drawLine(tint, Offset(12f, 19f), Offset(12f, 6f), ICON_STROKE, StrokeCap.Round)
            drawLine(tint, Offset(12f, 6f), Offset(6.5f, 11.5f), ICON_STROKE, StrokeCap.Round)
            drawLine(tint, Offset(12f, 6f), Offset(17.5f, 11.5f), ICON_STROKE, StrokeCap.Round)
        }
    }
}

/**
 * `label` — a tag: a rectangle whose right edge tapers to a point, with a small hole near the flat end.
 * Marks a manga's categories/shelves. Public so feature modules can render it beside category chips.
 */
@Composable
fun LabelIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp
) {
    Canvas(modifier.size(size)) {
        iconSpace {
            val tag =
                Path().apply {
                    moveTo(3.5f, 7f)
                    lineTo(14f, 7f)
                    lineTo(20f, 12f)
                    lineTo(14f, 17f)
                    lineTo(3.5f, 17f)
                    close()
                }
            drawPath(tag, tint, style = Stroke(width = ICON_STROKE, join = StrokeJoin.Round))
            drawCircle(tint, radius = 1.3f, center = Offset(7f, 12f))
        }
    }
}

/** `arrow_downward` — [ArrowUpwardIcon] mirrored; marks a **descending** sort. */
@Composable
fun ArrowDownwardIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp
) {
    Canvas(modifier.size(size)) {
        iconSpace {
            drawLine(tint, Offset(12f, 5f), Offset(12f, 18f), ICON_STROKE, StrokeCap.Round)
            drawLine(tint, Offset(12f, 18f), Offset(6.5f, 12.5f), ICON_STROKE, StrokeCap.Round)
            drawLine(tint, Offset(12f, 18f), Offset(17.5f, 12.5f), ICON_STROKE, StrokeCap.Round)
        }
    }
}

/**
 * `collections_bookmark` — the **Library** bottom-bar tab: an open book, two mirrored page leaves meeting
 * at a center spine. Shares [EmptyLibraryIcon]'s geometry at bar-glyph size so the tab reads as the same
 * "library" mark as the empty state.
 */
@Composable
fun LibraryTabIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp
) {
    Canvas(modifier.size(size)) {
        iconSpace {
            val leaf = Stroke(width = ICON_STROKE, cap = StrokeCap.Round, join = StrokeJoin.Round)
            val leftPage =
                Path().apply {
                    moveTo(12f, 7f)
                    lineTo(4.5f, 6f)
                    lineTo(4.5f, 17f)
                    lineTo(12f, 18f)
                }
            val rightPage =
                Path().apply {
                    moveTo(12f, 7f)
                    lineTo(19.5f, 6f)
                    lineTo(19.5f, 17f)
                    lineTo(12f, 18f)
                }
            drawPath(leftPage, tint, style = leaf)
            drawPath(rightPage, tint, style = leaf)
            drawLine(tint, Offset(12f, 7f), Offset(12f, 18f), ICON_STROKE, StrokeCap.Round)
        }
    }
}

/**
 * `explore` — the **Browse** bottom-bar tab: a compass, its ring circling a four-point needle. Marks the
 * source/discovery tab where new titles are found.
 */
@Composable
fun BrowseTabIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp
) {
    Canvas(modifier.size(size)) {
        iconSpace {
            drawCircle(tint, radius = 8f, center = Offset(12f, 12f), style = Stroke(width = ICON_STROKE))
            val needle =
                Path().apply {
                    moveTo(12f, 8f)
                    lineTo(14f, 14f)
                    lineTo(12f, 16f)
                    lineTo(10f, 14f)
                    close()
                }
            drawPath(needle, tint, style = Stroke(width = ICON_STROKE, join = StrokeJoin.Round))
        }
    }
}

/**
 * `show_chart` — the **Activity** bottom-bar tab: a rising trend line, echoing the screen's reading
 * curve. A single polyline so it reads as "activity over time" at a glance.
 */
@Composable
fun ActivityTabIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp
) {
    Canvas(modifier.size(size)) {
        iconSpace {
            val curve =
                Path().apply {
                    moveTo(4f, 15.5f)
                    lineTo(9f, 10.5f)
                    lineTo(13f, 13.5f)
                    lineTo(20f, 6.5f)
                }
            drawPath(
                curve,
                tint,
                style = Stroke(width = ICON_STROKE, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
    }
}

/**
 * `tune` — the **Settings** bottom-bar tab: three horizontal sliders, each with a knob at a different
 * position. The conventional "adjust settings" mark.
 */
@Composable
fun SettingsTabIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp
) {
    Canvas(modifier.size(size)) {
        iconSpace {
            drawLine(tint, Offset(4f, 7f), Offset(20f, 7f), ICON_STROKE, StrokeCap.Round)
            drawLine(tint, Offset(4f, 12f), Offset(20f, 12f), ICON_STROKE, StrokeCap.Round)
            drawLine(tint, Offset(4f, 17f), Offset(20f, 17f), ICON_STROKE, StrokeCap.Round)
            drawCircle(tint, radius = 2.4f, center = Offset(15f, 7f))
            drawCircle(tint, radius = 2.4f, center = Offset(8f, 12f))
            drawCircle(tint, radius = 2.4f, center = Offset(16f, 17f))
        }
    }
}

/**
 * `delete` — a trash can: a lid with a handle over a body with two vertical guides. Used for destructive
 * row actions (e.g. removing a history entry). Public so feature modules can render it.
 */
@Composable
fun TrashIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp
) {
    Canvas(modifier.size(size)) {
        iconSpace {
            // Lid + handle.
            drawLine(tint, Offset(4f, 6.5f), Offset(20f, 6.5f), ICON_STROKE, StrokeCap.Round)
            drawLine(tint, Offset(9.5f, 6.5f), Offset(9.5f, 4.5f), ICON_STROKE, StrokeCap.Round)
            drawLine(tint, Offset(14.5f, 6.5f), Offset(14.5f, 4.5f), ICON_STROKE, StrokeCap.Round)
            drawLine(tint, Offset(9.5f, 4.5f), Offset(14.5f, 4.5f), ICON_STROKE, StrokeCap.Round)
            // Body.
            val body =
                Path().apply {
                    moveTo(6f, 6.5f)
                    lineTo(7f, 20f)
                    lineTo(17f, 20f)
                    lineTo(18f, 6.5f)
                }
            drawPath(body, tint, style = Stroke(width = ICON_STROKE, join = StrokeJoin.Round))
            // Inner guides.
            drawLine(tint, Offset(10f, 9.5f), Offset(10.3f, 17f), ICON_STROKE, StrokeCap.Round)
            drawLine(tint, Offset(14f, 9.5f), Offset(13.7f, 17f), ICON_STROKE, StrokeCap.Round)
        }
    }
}

/** Runs [block] in the icon's native 24×24 space, scaled to fill the current [DrawScope]. */
private inline fun DrawScope.iconSpace(block: DrawScope.() -> Unit) {
    val factor = size.minDimension / ICON_VIEWPORT
    scale(factor, factor, pivot = Offset.Zero) { block() }
}