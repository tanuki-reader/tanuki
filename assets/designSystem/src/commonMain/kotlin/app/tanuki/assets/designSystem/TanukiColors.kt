package app.tanuki.assets.designSystem

import androidx.compose.ui.graphics.Color
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The Tanuki palette, Android dialect: the generated tokens ([TanukiNight] / [TanukiDay], from the design
 * system's `tokens.json`) mapped onto a Material 3 [ColorScheme] plus [TanukiColors] for what M3 has no
 * slot for. Never put a hex value here: refresh `iosApp/design/tokens.json` and re-run
 * `scripts/generate-ios-tokens.py`.
 */
@Immutable
data class TanukiColors(
    val accent: Color,
    val accentHover: Color,
    val accentTint: Color,
    val onAccent: Color,
    val surfaceHover: Color,
    val textFaint: Color,
    val line: Color,
    val lineStrong: Color,
    val focusRing: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val info: Color,
    val unread: Color,
    val scrim: Color,
    val coverScrim: Color,
)

/** Brand-extended tokens for Night. */
val tanukiDarkExtendedColors =
    with(TanukiNight) {
        TanukiColors(
            accent = Accent,
            accentHover = AccentHover,
            accentTint = AccentTint,
            onAccent = OnAccent,
            surfaceHover = SurfaceHover,
            textFaint = InkFaint,
            line = Line,
            lineStrong = LineStrong,
            focusRing = FocusRing,
            success = Success,
            warning = Warning,
            danger = Danger,
            info = Info,
            unread = Accent,
            scrim = Scrim,
            coverScrim = CoverScrim,
        )
    }

/** Brand-extended tokens for Day. */
val tanukiLightExtendedColors =
    with(TanukiDay) {
        TanukiColors(
            accent = Accent,
            accentHover = AccentHover,
            accentTint = AccentTint,
            onAccent = OnAccent,
            surfaceHover = SurfaceHover,
            textFaint = InkFaint,
            line = Line,
            lineStrong = LineStrong,
            focusRing = FocusRing,
            success = Success,
            warning = Warning,
            danger = Danger,
            info = Info,
            unread = Accent,
            scrim = Scrim,
            coverScrim = CoverScrim,
        )
    }

/** Material 3 color scheme for Night. */
fun tanukiDarkColorScheme(): ColorScheme =
    with(TanukiNight) {
        darkColorScheme(
            primary = Accent,
            onPrimary = OnAccent,
            primaryContainer = AccentTint,
            onPrimaryContainer = Ink,
            secondary = SurfaceHover,
            onSecondary = Ink,
            background = Canvas,
            onBackground = Ink,
            surface = Surface,
            onSurface = Ink,
            surfaceVariant = SurfaceRaised,
            onSurfaceVariant = InkMuted,
            surfaceContainerLowest = Canvas,
            surfaceContainerLow = Surface,
            surfaceContainer = Surface,
            surfaceContainerHigh = SurfaceRaised,
            surfaceContainerHighest = SurfaceHover,
            outline = LineStrong,
            outlineVariant = Line,
            error = Danger,
            onError = OnAccent,
            scrim = Scrim,
        )
    }

/** Material 3 color scheme for Day. */
fun tanukiLightColorScheme(): ColorScheme =
    with(TanukiDay) {
        lightColorScheme(
            primary = Accent,
            onPrimary = OnAccent,
            primaryContainer = AccentTint,
            onPrimaryContainer = Ink,
            secondary = SurfaceHover,
            onSecondary = Ink,
            background = Canvas,
            onBackground = Ink,
            surface = Surface,
            onSurface = Ink,
            surfaceVariant = SurfaceRaised,
            onSurfaceVariant = InkMuted,
            surfaceContainerLowest = Canvas,
            surfaceContainerLow = Surface,
            surfaceContainer = Surface,
            surfaceContainerHigh = SurfaceRaised,
            surfaceContainerHighest = SurfaceHover,
            outline = LineStrong,
            outlineVariant = Line,
            error = Danger,
            onError = OnAccent,
            scrim = Scrim,
        )
    }

val LocalTanukiColors = staticCompositionLocalOf { tanukiDarkExtendedColors }
