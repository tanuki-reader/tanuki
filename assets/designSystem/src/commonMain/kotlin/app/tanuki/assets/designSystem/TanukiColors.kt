package app.tanuki.assets.designSystem

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Tanuki color tokens — the **Android (Material You) dialect**, synced from the design system's
 * `tokens/colors.css` (single source of truth: the remote claude.ai/design project).
 *
 * Tanuki is dark-first and near-monochrome with one warm tanuki-orange accent, and also ships a light
 * dialect (`data-theme="light"` in the design tokens): a warm paper canvas with the same accent,
 * slightly deepened so it holds on white. The values below are verbatim from the design tokens. Slots
 * that map cleanly onto Material 3 are exposed through [tanukiDarkColorScheme] / [tanukiLightColorScheme];
 * the brand-specific extras that have no Material slot (the surface-3 hover layer, tertiary text, status
 * colors, accent variants) live in [TanukiColors] and are reached via [LocalTanukiColors].
 */
object TanukiPalette {
    // Brand / accent
    val Orange = Color(0xFFE8731C) // primary accent — tanuki fur
    val OrangeLight = Color(0xFFF58A36) // hover / lighter accent
    val OrangePress = Color(0x26B85B16) // pressed accent wash (15%)
    val OrangeTint = Color(0x1FE8731C) // selected / tinted fill (12%)

    // Neutral canvas (dark)
    val Bg = Color(0xFF0C0C0C) // app background
    val Surface = Color(0xFF141414) // cards, sheets, bars
    val Surface2 = Color(0xFF1E1E1E) // raised rows, chips, inputs
    val Surface3 = Color(0xFF2A2A2A) // hover surface, pressed rows

    // Text
    val Text1 = Color(0xFFEEEEEE) // primary text
    val Text2 = Color(0xFFAAAAAA) // secondary text
    val Text3 = Color(0xFF666666) // tertiary / disabled
    val OnAccent = Color(0xFF0C0C0C) // text/icon on orange fill

    // Lines & dividers
    val Border = Color(0xFF2A2A2A) // hairline divider
    val BorderStrong = Color(0xFF383838) // input outline, card edge

    // Status / semantic
    val Success = Color(0xFF45C46B) // read / up-to-date
    val Warning = Color(0xFFF2B135) // update available
    val Danger = Color(0xFFF0564B) // delete / error
    val Info = Color(0xFF4FA3EB) // tracker / sync

    // Scrim & overlay
    val Scrim = Color(0x99000000) // dialog backdrop (0.60)
    val ScrimSoft = Color(0x59000000) // reader chrome scrim (0.35)
}

/**
 * Light dialect — the `:root[data-theme="light"]` overrides from `tokens/colors.css`. Same warm tanuki
 * accent (deepened so it holds on white) on a warm paper canvas; status colors are darkened for AA
 * contrast on light surfaces. Only the raw values differ; the semantic mapping is identical to dark.
 */
object TanukiPaletteLight {
    // Brand / accent — deepened for contrast on white
    val Orange = Color(0xFFD9620F) // primary accent
    val OrangeLight = Color(0xFFC1560C) // hover goes darker on light bg
    val OrangePress = Color(0x26D9620F) // pressed accent wash (15%)
    val OrangeTint = Color(0x17D9620F) // selected / tinted fill (9%)

    // Neutral canvas (light)
    val Bg = Color(0xFFF6F4F1) // warm paper background
    val Surface = Color(0xFFFFFFFF) // cards, sheets, bars
    val Surface2 = Color(0xFFF0EDE9) // raised rows, chips, inputs
    val Surface3 = Color(0xFFE6E2DC) // hover surface, pressed rows

    // Text
    val Text1 = Color(0xFF1A1817) // primary text
    val Text2 = Color(0xFF6B6560) // secondary text
    val Text3 = Color(0xFFA29B94) // tertiary / disabled
    val OnAccent = Color(0xFFFFFFFF) // text/icon on orange fill

    // Lines & dividers
    val Border = Color(0xFFE4DFD9) // hairline divider
    val BorderStrong = Color(0xFFD2CCC4) // input outline, card edge

    // Status / semantic — darkened for AA on white
    val Success = Color(0xFF1E9E4A) // read / up-to-date
    val Warning = Color(0xFFC98A08) // update available
    val Danger = Color(0xFFD93B2F) // delete / error
    val Info = Color(0xFF2C7FD1) // tracker / sync

    // Scrim & overlay
    val Scrim = Color(0x73000000) // dialog backdrop (0.45)
    val ScrimSoft = Color(0x40000000) // reader chrome scrim (0.25)
}

/**
 * Brand colors that have no Material 3 slot. Components read these via `LocalTanukiColors.current`;
 * everything that maps to a Material slot should be read from `MaterialTheme.colorScheme` instead.
 */
@Immutable
data class TanukiColors(
    val accent: Color,
    val accentHover: Color,
    val accentPress: Color,
    val accentTint: Color,
    val surfaceHover: Color,
    val textFaint: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val info: Color,
    val unread: Color,
    val scrim: Color,
    val scrimSoft: Color
)

/** Brand-extended tokens for the dark dialect. */
val tanukiDarkExtendedColors =
    TanukiColors(
        accent = TanukiPalette.Orange,
        accentHover = TanukiPalette.OrangeLight,
        accentPress = TanukiPalette.OrangePress,
        accentTint = TanukiPalette.OrangeTint,
        surfaceHover = TanukiPalette.Surface3,
        textFaint = TanukiPalette.Text3,
        success = TanukiPalette.Success,
        warning = TanukiPalette.Warning,
        danger = TanukiPalette.Danger,
        info = TanukiPalette.Info,
        unread = TanukiPalette.Orange,
        scrim = TanukiPalette.Scrim,
        scrimSoft = TanukiPalette.ScrimSoft
    )

/** Brand-extended tokens for the light dialect. */
val tanukiLightExtendedColors =
    TanukiColors(
        accent = TanukiPaletteLight.Orange,
        accentHover = TanukiPaletteLight.OrangeLight,
        accentPress = TanukiPaletteLight.OrangePress,
        accentTint = TanukiPaletteLight.OrangeTint,
        surfaceHover = TanukiPaletteLight.Surface3,
        textFaint = TanukiPaletteLight.Text3,
        success = TanukiPaletteLight.Success,
        warning = TanukiPaletteLight.Warning,
        danger = TanukiPaletteLight.Danger,
        info = TanukiPaletteLight.Info,
        unread = TanukiPaletteLight.Orange,
        scrim = TanukiPaletteLight.Scrim,
        scrimSoft = TanukiPaletteLight.ScrimSoft
    )

/** Material 3 color scheme built from the Tanuki **dark** tokens. */
fun tanukiDarkColorScheme(): ColorScheme =
    darkColorScheme(
        primary = TanukiPalette.Orange,
        onPrimary = TanukiPalette.OnAccent,
        primaryContainer = TanukiPalette.OrangeTint,
        onPrimaryContainer = TanukiPalette.Text1,
        secondary = TanukiPalette.Surface3,
        onSecondary = TanukiPalette.Text1,
        background = TanukiPalette.Bg,
        onBackground = TanukiPalette.Text1,
        surface = TanukiPalette.Surface,
        onSurface = TanukiPalette.Text1,
        surfaceVariant = TanukiPalette.Surface2,
        onSurfaceVariant = TanukiPalette.Text2,
        outline = TanukiPalette.BorderStrong,
        outlineVariant = TanukiPalette.Border,
        error = TanukiPalette.Danger,
        onError = TanukiPalette.OnAccent,
        scrim = TanukiPalette.Scrim
    )

/** Material 3 color scheme built from the Tanuki **light** tokens. */
fun tanukiLightColorScheme(): ColorScheme =
    lightColorScheme(
        primary = TanukiPaletteLight.Orange,
        onPrimary = TanukiPaletteLight.OnAccent,
        primaryContainer = TanukiPaletteLight.OrangeTint,
        onPrimaryContainer = TanukiPaletteLight.Text1,
        secondary = TanukiPaletteLight.Surface3,
        onSecondary = TanukiPaletteLight.Text1,
        background = TanukiPaletteLight.Bg,
        onBackground = TanukiPaletteLight.Text1,
        surface = TanukiPaletteLight.Surface,
        onSurface = TanukiPaletteLight.Text1,
        surfaceVariant = TanukiPaletteLight.Surface2,
        onSurfaceVariant = TanukiPaletteLight.Text2,
        outline = TanukiPaletteLight.BorderStrong,
        outlineVariant = TanukiPaletteLight.Border,
        error = TanukiPaletteLight.Danger,
        onError = TanukiPaletteLight.OnAccent,
        scrim = TanukiPaletteLight.Scrim
    )

val LocalTanukiColors = staticCompositionLocalOf { tanukiDarkExtendedColors }
