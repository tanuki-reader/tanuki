package app.tanuki.assets.designSystem.component

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The Android icon set: Material Symbols Rounded ([TanukiSymbols]), one composable per Tanuki icon name so
 * callers name the meaning, not the glyph. Each takes a [tint] from theme tokens (`ink` at rest,
 * `ink-muted` secondary, `accent` active, a status colour for status). Icons with a selected state take
 * `filled`: FILL 0 at rest, FILL 1 when selected, as the design system asks. Never draw icons by hand.
 */

/** Builds an [ImageVector] from one Material Symbols path in a [box]-unit square whose top edge is [top]. */
internal fun materialSymbol(
    name: String,
    box: Float,
    top: Float,
    pathData: String,
): ImageVector =
    ImageVector
        .Builder(name, 24.dp, 24.dp, box, box)
        .group(translationY = -top) { addPath(addPathNodes(pathData), fill = SolidColor(Color.Black)) }
        .build()

@Composable
private fun Symbol(
    symbol: ImageVector,
    tint: Color,
    modifier: Modifier,
    size: Dp,
) = Icon(symbol, contentDescription = null, tint = tint, modifier = modifier.size(size))

/** `search`. */
@Composable
fun SearchIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
) = Symbol(TanukiSymbols.Search, tint, modifier, size)

/** `arrow_back`, to leave search or a detail screen. */
@Composable
internal fun BackIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
) = Symbol(TanukiSymbols.ArrowBack, tint, modifier, size)

/** `close`: clears a field, or dismisses a modal surface that is not on the back stack. */
@Composable
fun CloseIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
) = Symbol(TanukiSymbols.Close, tint, modifier, size)

/** `filter_list`. */
@Composable
fun FilterIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
) = Symbol(TanukiSymbols.FilterList, tint, modifier, size)

/** `arrow_upward`: ascending sort. */
@Composable
fun ArrowUpwardIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
) = Symbol(TanukiSymbols.ArrowUpward, tint, modifier, size)

/** `label`: categories. */
@Composable
fun LabelIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
) = Symbol(TanukiSymbols.Label, tint, modifier, size)

/** `arrow_downward`: descending sort. */
@Composable
fun ArrowDownwardIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
) = Symbol(TanukiSymbols.ArrowDownward, tint, modifier, size)

/** `collections_bookmark`: the Library tab. */
@Composable
fun LibraryTabIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    filled: Boolean = false,
) = Symbol(if (filled) TanukiSymbols.CollectionsBookmarkFilled else TanukiSymbols.CollectionsBookmark, tint, modifier, size)

/** `home`: the Home tab. */
@Composable
fun HomeTabIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    filled: Boolean = false,
) = Symbol(if (filled) TanukiSymbols.HomeFilled else TanukiSymbols.Home, tint, modifier, size)

/** `expand_more`: a disclosure that expands in place. */
@Composable
fun ChevronDownIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
) = Symbol(TanukiSymbols.ExpandMore, tint, modifier, size)

/** `insights`: the Activity tab. */
@Composable
fun ActivityTabIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    filled: Boolean = false,
) = Symbol(if (filled) TanukiSymbols.InsightsFilled else TanukiSymbols.Insights, tint, modifier, size)

/** `settings`: the Settings tab. */
@Composable
fun SettingsTabIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    filled: Boolean = false,
) = Symbol(if (filled) TanukiSymbols.SettingsFilled else TanukiSymbols.Settings, tint, modifier, size)

/** `delete`. */
@Composable
fun TrashIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
) = Symbol(TanukiSymbols.Delete, tint, modifier, size)

/** `bookmark`; filled when set. */
@Composable
fun BookmarkIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
    filled: Boolean = false,
) = Symbol(if (filled) TanukiSymbols.BookmarkFilled else TanukiSymbols.Bookmark, tint, modifier, size)


/** `contrast`: the theme setting. */
@Composable
fun ContrastIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
) = Symbol(TanukiSymbols.Contrast, tint, modifier, size)

/** `verified_user`: a security check (Cloudflare). */
@Composable
fun ShieldCheckIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
) = Symbol(TanukiSymbols.VerifiedUser, tint, modifier, size)

/** `lock`: a sign-in. */
@Composable
fun LockIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
) = Symbol(TanukiSymbols.Lock, tint, modifier, size)

/** `extension`: sources. */
@Composable
fun ExtensionIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
) = Symbol(TanukiSymbols.Extension, tint, modifier, size)

/** `download`. */
@Composable
fun DownloadIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
) = Symbol(TanukiSymbols.Download, tint, modifier, size)

/** `check`. */
@Composable
fun CheckIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
) = Symbol(TanukiSymbols.Check, tint, modifier, size)

/** `remove_done`: mark as unread. */
@Composable
fun CheckOffIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
) = Symbol(TanukiSymbols.RemoveDone, tint, modifier, size)

/** `keyboard_double_arrow_up`: select everything above. */
@Composable
fun DoubleArrowUpIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
) = Symbol(TanukiSymbols.KeyboardDoubleArrowUp, tint, modifier, size)

/** `keyboard_double_arrow_down`: select everything below. */
@Composable
fun DoubleArrowDownIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
) = Symbol(TanukiSymbols.KeyboardDoubleArrowDown, tint, modifier, size)

/** `select_all`. */
@Composable
fun SelectAllIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
) = Symbol(TanukiSymbols.SelectAll, tint, modifier, size)

/** `download_done`: downloaded; tint it `success`. */
@Composable
fun DownloadedIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
) = Symbol(TanukiSymbols.DownloadDone, tint, modifier, size)

/** `error`; tint it `danger`. */
@Composable
fun ErrorIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
) = Symbol(TanukiSymbols.Error, tint, modifier, size)

/** `pause`. */
@Composable
fun PauseIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
) = Symbol(TanukiSymbols.Pause, tint, modifier, size)

/** `play_arrow`. */
@Composable
fun PlayIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
) = Symbol(TanukiSymbols.PlayArrow, tint, modifier, size)

/** `wifi`: Wi-Fi only downloads. */
@Composable
fun WifiIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
) = Symbol(TanukiSymbols.Wifi, tint, modifier, size)

/** The empty-state glyph: the leaf-page mark in one ink (`ink-faint` by convention), never a drawn icon. */
@Composable
fun EmptyLibraryIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
) = TanukiLogo(modifier = modifier, size = size, tint = tint)
