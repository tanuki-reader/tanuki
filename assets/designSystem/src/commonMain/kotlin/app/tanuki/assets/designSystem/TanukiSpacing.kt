package app.tanuki.assets.designSystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Tanuki spacing, radii and hit-target tokens — Android dialect, synced from `tokens/spacing.css`.
 *
 * 8pt grid with 4px half-steps. Radii use the **Material (tight)** family; the iOS Liquid Glass radii
 * (10px+) live in the SwiftUI token set. Material elevation here is dp-based (Compose `tonalElevation`
 * / `shadowElevation`); the iOS glass blur/shadow tokens are SwiftUI-only.
 */
@Immutable
object TanukiSpacing {
    // 8pt grid (with 4px half-steps)
    val none: Dp = 0.dp
    val xxs: Dp = 2.dp // --space-1
    val xs: Dp = 4.dp // --space-2
    val sm: Dp = 8.dp // --space-3
    val md: Dp = 12.dp // --space-4
    val lg: Dp = 16.dp // --space-5
    val xl: Dp = 20.dp // --space-6
    val xxl: Dp = 24.dp // --space-7
    val x3l: Dp = 32.dp // --space-8
    val x4l: Dp = 40.dp // --space-9
    val x5l: Dp = 48.dp // --space-10
    val x6l: Dp = 64.dp // --space-12

    // Screen gutters
    val gutter: Dp = 16.dp
    val gutterTight: Dp = 12.dp
    val gridGap: Dp = 12.dp // library cover grid gap

    // Hit targets & bar heights
    val tapMin: Dp = 44.dp // minimum interactive target
    val barHeight: Dp = 56.dp
    val appBarHeight: Dp = 56.dp
}

/** Corner radii — Material (tight) family. */
@Immutable
object TanukiRadii {
    val container: Dp = 4.dp // --r-android: Material filled container
    val chip: Dp = 8.dp // Material chip / tag
    val cover: Dp = 6.dp // manga cover art corners
    val sheet: Dp = 16.dp // bottom sheet
    val pill: Dp = 999.dp // search pill, FAB, badges

    val containerShape = RoundedCornerShape(container)
    val chipShape = RoundedCornerShape(chip)
    val coverShape = RoundedCornerShape(cover)
    val sheetShape = RoundedCornerShape(topStart = sheet, topEnd = sheet)
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