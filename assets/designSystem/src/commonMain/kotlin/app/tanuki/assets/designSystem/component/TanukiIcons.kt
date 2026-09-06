package app.tanuki.assets.designSystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
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

/**
 * `close` — a rounded X. Clears the search field, and dismisses a modal surface that is not part of the
 * back stack (the site-session layer), where an X reads as "close this" rather than "go back a screen".
 */
@Composable
fun CloseIcon(
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
fun FilterIcon(
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
 * `home` — the **Home** bottom-bar tab: a house, a roof gable over a body with a door. Marks the app's
 * landing surface (For You + Sources), which replaced the old discovery-only "Browse" tab.
 */
@Composable
fun HomeTabIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp
) {
    Canvas(modifier.size(size)) {
        iconSpace {
            val stroke = Stroke(width = ICON_STROKE, cap = StrokeCap.Round, join = StrokeJoin.Round)
            // Roof gable + body walls as one open outline.
            val house =
                Path().apply {
                    moveTo(4f, 11f)
                    lineTo(12f, 4.5f)
                    lineTo(20f, 11f)
                    moveTo(5.5f, 10f)
                    lineTo(5.5f, 20f)
                    lineTo(18.5f, 20f)
                    lineTo(18.5f, 10f)
                }
            drawPath(house, tint, style = stroke)
            // Door.
            val door =
                Path().apply {
                    moveTo(10f, 20f)
                    lineTo(10f, 14.5f)
                    lineTo(14f, 14.5f)
                    lineTo(14f, 20f)
                }
            drawPath(door, tint, style = stroke)
        }
    }
}

/**
 * `expand_more` — a downward chevron. Marks a collapsible/disclosure control (e.g. a language group in
 * the source list); callers rotate it 180° when the section is expanded. Public so feature modules can
 * render it in row headers.
 */
@Composable
fun ChevronDownIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp
) {
    Canvas(modifier.size(size)) {
        iconSpace {
            drawLine(tint, Offset(6.5f, 9.5f), Offset(12f, 15f), ICON_STROKE, StrokeCap.Round)
            drawLine(tint, Offset(17.5f, 9.5f), Offset(12f, 15f), ICON_STROKE, StrokeCap.Round)
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

/**
 * `bookmark` — a ribbon bookmark: a rectangle whose bottom edge notches up to a center point. Marks the
 * user's reading status for a series (Reading / Completed / …). Public so feature modules can render it
 * beside the status label.
 */
@Composable
fun BookmarkIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp
) {
    Canvas(modifier.size(size)) {
        iconSpace {
            val ribbon =
                Path().apply {
                    moveTo(6.5f, 4.5f)
                    lineTo(17.5f, 4.5f)
                    lineTo(17.5f, 19.5f)
                    lineTo(12f, 14.5f)
                    lineTo(6.5f, 19.5f)
                    close()
                }
            drawPath(ribbon, tint, style = Stroke(width = ICON_STROKE, join = StrokeJoin.Round))
        }
    }
}

/**
 * `chevron_right` — a rightward chevron. Marks a drill-down list row (a settings category, a source)
 * that pushes a sub-screen. Public so feature modules can render it as a row's trailing affordance.
 */
@Composable
fun ChevronRightIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp
) {
    Canvas(modifier.size(size)) {
        iconSpace {
            drawLine(tint, Offset(9.5f, 6.5f), Offset(15f, 12f), ICON_STROKE, StrokeCap.Round)
            drawLine(tint, Offset(9.5f, 17.5f), Offset(15f, 12f), ICON_STROKE, StrokeCap.Round)
        }
    }
}

/**
 * `contrast` — a circle whose right half is filled, the conventional "appearance / theme" mark (light vs.
 * dark). Public so feature modules can render it beside the appearance settings row.
 */
@Composable
fun ContrastIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp
) {
    Canvas(modifier.size(size)) {
        iconSpace {
            drawCircle(tint, radius = 8f, center = Offset(12f, 12f), style = Stroke(width = ICON_STROKE))
            // Right half filled: sweep 180° clockwise from the top (−90°) through the right to the bottom.
            drawArc(
                color = tint,
                startAngle = -90f,
                sweepAngle = 180f,
                useCenter = true,
                topLeft = Offset(4f, 4f),
                size = Size(16f, 16f)
            )
        }
    }
}

/**
 * `verified_user` — a shield with a check inside. Marks a security checkpoint the site is putting in
 * front of the user (the Cloudflare challenge), rather than a failure: the check says the goal is to be
 * let through.
 */
@Composable
fun ShieldCheckIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp
) {
    Canvas(modifier.size(size)) {
        iconSpace {
            val shield =
                Path().apply {
                    moveTo(12f, 2.8f)
                    lineTo(19.2f, 5.8f)
                    lineTo(19.2f, 11.6f)
                    // The tapering lower half: both flanks curve in to a rounded point under the centre.
                    quadraticTo(19.2f, 17.6f, 12f, 21.2f)
                    quadraticTo(4.8f, 17.6f, 4.8f, 11.6f)
                    lineTo(4.8f, 5.8f)
                    close()
                }
            drawPath(shield, tint, style = Stroke(width = ICON_STROKE, join = StrokeJoin.Round))
            drawLine(tint, Offset(8.8f, 11.8f), Offset(11f, 14f), ICON_STROKE, StrokeCap.Round)
            drawLine(tint, Offset(11f, 14f), Offset(15.2f, 9.8f), ICON_STROKE, StrokeCap.Round)
        }
    }
}

/**
 * `lock` — a padlock: a rounded body under a shackle arc. Marks a surface asking the user to sign in to
 * a source.
 */
@Composable
fun LockIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp
) {
    Canvas(modifier.size(size)) {
        iconSpace {
            val shackle =
                Path().apply {
                    moveTo(8.2f, 10.5f)
                    lineTo(8.2f, 8f)
                    quadraticTo(8.2f, 4.2f, 12f, 4.2f)
                    quadraticTo(15.8f, 4.2f, 15.8f, 8f)
                    lineTo(15.8f, 10.5f)
                }
            drawPath(shackle, tint, style = Stroke(width = ICON_STROKE, cap = StrokeCap.Round))
            drawRoundRect(
                color = tint,
                topLeft = Offset(4.8f, 10.5f),
                size = Size(14.4f, 9f),
                cornerRadius = CornerRadius(2.4f, 2.4f),
                style = Stroke(width = ICON_STROKE)
            )
        }
    }
}

/**
 * `extension` — the puzzle-piece mark for extensions: a rounded square with a tab budding from its top
 * edge and a matching socket bitten out of its left. Marks the source-management surface, where sources
 * are the app's extensions.
 */
@Composable
fun ExtensionIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp
) {
    Canvas(modifier.size(size)) {
        iconSpace {
            val piece =
                Path().apply {
                    // Top edge, interrupted by the outward tab.
                    moveTo(9.5f, 4.5f)
                    quadraticTo(9.5f, 1.9f, 12f, 1.9f)
                    quadraticTo(14.5f, 1.9f, 14.5f, 4.5f)
                    lineTo(19.5f, 4.5f)
                    lineTo(19.5f, 19.5f)
                    lineTo(4.5f, 19.5f)
                    // Left edge, interrupted by the inward socket — the tab's negative, so two pieces read
                    // as interlocking rather than as one shape repeated.
                    lineTo(4.5f, 14.5f)
                    quadraticTo(1.9f, 14.5f, 1.9f, 12f)
                    quadraticTo(1.9f, 9.5f, 4.5f, 9.5f)
                    lineTo(4.5f, 4.5f)
                    close()
                }
            drawPath(piece, tint, style = Stroke(width = ICON_STROKE, join = StrokeJoin.Round))
        }
    }
}

/**
 * `download` — an arrow falling onto a tray: the conventional install mark. Paired with [TrashIcon] on
 * the source-management rows, where the two are each other's inverse.
 */
@Composable
fun DownloadIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp
) {
    Canvas(modifier.size(size)) {
        iconSpace {
            // Shaft and head.
            drawLine(tint, Offset(12f, 3.5f), Offset(12f, 14.5f), ICON_STROKE, StrokeCap.Round)
            drawLine(tint, Offset(7.5f, 10f), Offset(12f, 14.5f), ICON_STROKE, StrokeCap.Round)
            drawLine(tint, Offset(16.5f, 10f), Offset(12f, 14.5f), ICON_STROKE, StrokeCap.Round)
            // Tray: an open-topped U, so the arrow reads as landing in it rather than crossing a line.
            val tray =
                Path().apply {
                    moveTo(4.5f, 15.5f)
                    lineTo(4.5f, 20f)
                    lineTo(19.5f, 20f)
                    lineTo(19.5f, 15.5f)
                }
            drawPath(tray, tint, style = Stroke(width = ICON_STROKE, join = StrokeJoin.Round, cap = StrokeCap.Round))
        }
    }
}

/**
 * `check` — a rounded tick. The app's single "done / read / selected" mark: it confirms a selected row in
 * a multi-select and labels the action that marks chapters read, so the same glyph means the same thing
 * in the list and in the bar acting on it.
 */
@Composable
fun CheckIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp
) {
    Canvas(modifier.size(size)) {
        iconSpace { drawCheck(tint) }
    }
}

/**
 * `remove_done` — [CheckIcon] struck through, for the action that takes chapters back to unread. Drawn as
 * the tick plus a slash rather than a different glyph, so "mark unread" reads as the literal undoing of
 * "mark read" instead of a second thing to learn.
 *
 * The slash runs **down**-right, against the tick's long arm rather than along it. The other diagonal
 * looks like the obvious choice and is the wrong one: the tick's long arm already travels up-right at
 * very nearly the same angle, so a slash drawn that way lies along it and reads as one thickened stroke —
 * a check that looks badly drawn rather than a check that has been cancelled. Crossing it near the middle
 * is what makes the strike-through legible at 20dp.
 */
@Composable
fun CheckOffIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp
) {
    Canvas(modifier.size(size)) {
        iconSpace {
            drawCheck(tint)
            drawLine(tint, Offset(5f, 5f), Offset(19f, 19f), ICON_STROKE, StrokeCap.Round)
        }
    }
}

/**
 * `keyboard_double_arrow_up` — two stacked chevrons pointing up: "everything from here upward". Marks the
 * multi-select's extend-upward action; pairs with [DoubleArrowDownIcon]. Deliberately *double* chevrons,
 * so it can't be misread as the single-arrow [ArrowUpwardIcon] that means sort direction.
 */
@Composable
fun DoubleArrowUpIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp
) {
    Canvas(modifier.size(size)) {
        iconSpace {
            drawChevronUp(tint, apexY = 6f)
            drawChevronUp(tint, apexY = 13f)
        }
    }
}

/** `keyboard_double_arrow_down` — [DoubleArrowUpIcon] mirrored: "everything from here downward". */
@Composable
fun DoubleArrowDownIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp
) {
    Canvas(modifier.size(size)) {
        iconSpace {
            drawChevronDown(tint, apexY = 11f)
            drawChevronDown(tint, apexY = 18f)
        }
    }
}

/**
 * `select_all` — a dashed square (the whole list) around a solid one (what gets taken). Toggles a
 * multi-select between "everything" and "nothing"; the dashes are what keep it from reading as a plain
 * card or checkbox.
 */
@Composable
fun SelectAllIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp
) {
    Canvas(modifier.size(size)) {
        iconSpace {
            drawRoundRect(
                color = tint,
                topLeft = Offset(3.5f, 3.5f),
                size = Size(17f, 17f),
                cornerRadius = CornerRadius(2f, 2f),
                style =
                    Stroke(
                        width = ICON_STROKE,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 2.6f))
                    )
            )
            drawRoundRect(
                color = tint,
                topLeft = Offset(8f, 8f),
                size = Size(8f, 8f),
                cornerRadius = CornerRadius(1.5f, 1.5f)
            )
        }
    }
}

/**
 * `check_circle` (filled) — a solid disc with the tick knocked out of it, for a chapter that is fully
 * downloaded and readable offline.
 *
 * Filled rather than outlined, and the only filled glyph in the set, because it is the one download state
 * that is *finished*: the queue states are all outlines, so a solid mark reads as an endpoint at a glance
 * without the user comparing shapes. The tick is drawn in [knockout] rather than as a hole, so the glyph
 * works on any surface the row happens to sit on — including the accent wash of a selected row.
 *
 * @param tint the disc colour; the design system's `success` at every call site.
 * @param knockout the colour the tick is punched in — the surface behind the glyph.
 */
@Composable
fun DownloadedIcon(
    tint: Color,
    knockout: Color,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp
) {
    Canvas(modifier.size(size)) {
        iconSpace {
            drawCircle(color = tint, radius = 10f, center = Offset(12f, 12f))
            val tick =
                Path().apply {
                    moveTo(7.2f, 12.2f)
                    lineTo(10.6f, 15.6f)
                    lineTo(16.8f, 8.8f)
                }
            drawPath(
                tick,
                knockout,
                style = Stroke(width = ICON_STROKE, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
    }
}

/**
 * `error_outline` — an exclamation inside a ring, for a download that gave up.
 *
 * An outline, like the rest of the queue states, so the finished state stays the only filled one. The
 * exclamation is a bar and a separate dot rather than one tapered stroke: at 20dp a tapered mark loses its
 * gap and reads as a plain line, which is the difference between "something went wrong" and "in progress".
 */
@Composable
fun ErrorIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp
) {
    Canvas(modifier.size(size)) {
        iconSpace {
            drawCircle(color = tint, radius = 9f, center = Offset(12f, 12f), style = Stroke(width = ICON_STROKE))
            drawLine(tint, Offset(12f, 7.5f), Offset(12f, 13f), ICON_STROKE, StrokeCap.Round)
            drawCircle(color = tint, radius = 1.15f, center = Offset(12f, 16.4f))
        }
    }
}

/** The shared tick geometry behind [CheckIcon] and [CheckOffIcon]. */
private fun DrawScope.drawCheck(tint: Color) {
    val tick =
        Path().apply {
            moveTo(4.5f, 12.5f)
            lineTo(9.5f, 17.5f)
            lineTo(19.5f, 6.5f)
        }
    drawPath(tick, tint, style = Stroke(width = ICON_STROKE, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** One upward chevron with its apex at [apexY], for the stacked double arrows. */
private fun DrawScope.drawChevronUp(
    tint: Color,
    apexY: Float
) {
    drawLine(tint, Offset(5.5f, apexY + 5f), Offset(12f, apexY), ICON_STROKE, StrokeCap.Round)
    drawLine(tint, Offset(18.5f, apexY + 5f), Offset(12f, apexY), ICON_STROKE, StrokeCap.Round)
}

/** One downward chevron with its apex at [apexY]. */
private fun DrawScope.drawChevronDown(
    tint: Color,
    apexY: Float
) {
    drawLine(tint, Offset(5.5f, apexY - 5f), Offset(12f, apexY), ICON_STROKE, StrokeCap.Round)
    drawLine(tint, Offset(18.5f, apexY - 5f), Offset(12f, apexY), ICON_STROKE, StrokeCap.Round)
}

/** Runs [block] in the icon's native 24×24 space, scaled to fill the current [DrawScope]. */
private inline fun DrawScope.iconSpace(block: DrawScope.() -> Unit) {
    val factor = size.minDimension / ICON_VIEWPORT
    scale(factor, factor, pivot = Offset.Zero) { block() }
}