package app.tanuki.assets.designSystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Tanuki spacing, radii and hit-target tokens, Android dialect, from the design system's `tokens.json`.
 * 4pt grid. Elevation is a step up the surface ladder, not a shadow; [TanukiElevation] is for the few
 * things that float (menus, snackbars, sheets).
 */
@Immutable
object TanukiSpacing {
    val none: Dp = 0.dp
    val xxs: Dp = 2.dp // space-0-5
    val xs: Dp = 4.dp // space-1
    val sm: Dp = 8.dp // space-2
    val md: Dp = 12.dp // space-3
    val lg: Dp = 16.dp // space-4
    val xl: Dp = 20.dp // space-5
    val xxl: Dp = 24.dp // space-6
    val x3l: Dp = 32.dp // space-8
    val x4l: Dp = 40.dp
    val x5l: Dp = 48.dp // space-12
    val x6l: Dp = 64.dp // space-16

    // Screen gutters
    val gutter: Dp = 16.dp // space-4 on phones
    val gutterTight: Dp = 12.dp
    val gridGap: Dp = 8.dp // cover-grid gap on phones (space-2)

    // Hit targets & bar heights
    val tapMin: Dp = 48.dp // tap-android
    val barHeight: Dp = 64.dp // bar-android
    val appBarHeight: Dp = 64.dp // bar-android
}

/** Corner radii (`radius-*`) and the shape each component family takes on Android. */
@Immutable
object TanukiRadii {
    val xs: Dp = 4.dp // cover thumbnails at 40dp and below
    val chip: Dp = 8.dp // radius-sm: cover art, filter chips
    val cover: Dp = 8.dp // radius-sm
    val field: Dp = 12.dp // radius-md: text fields, cards nested in lists
    val card: Dp = 16.dp // radius-lg: cards, menus
    val sheet: Dp = 28.dp // radius-xl: bottom sheets (top corners), dialogs
    val pill: Dp = 999.dp // radius-full: buttons, badges, switches, search

    val chipShape = RoundedCornerShape(chip)
    val coverShape = RoundedCornerShape(cover)
    val fieldShape = RoundedCornerShape(field)
    val cardShape = RoundedCornerShape(card)
    val menuShape = RoundedCornerShape(card)
    val sheetShape = RoundedCornerShape(topStart = sheet, topEnd = sheet)
    val dialogShape = RoundedCornerShape(sheet)
    val pillShape = RoundedCornerShape(pill)
}

/** Material elevation steps (dp). Used as Compose `tonalElevation` / `shadowElevation`. */
@Immutable
object TanukiElevation {
    val level0: Dp = 0.dp
    val level1: Dp = 1.dp
    val level2: Dp = 3.dp
    val level3: Dp = 6.dp
}

val LocalTanukiSpacing = staticCompositionLocalOf { TanukiSpacing }