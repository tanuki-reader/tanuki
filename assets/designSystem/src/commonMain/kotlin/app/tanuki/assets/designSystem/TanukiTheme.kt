package app.tanuki.assets.designSystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * Root theme for all Tanuki Compose UI. Wraps Material 3 with the Tanuki color scheme and typography,
 * and provides the brand-extended tokens ([TanukiColors], spacing, full type set) through composition
 * locals.
 *
 * Tanuki is dark-first but ships both dialects; [darkTheme] follows the system by default. Access
 * tokens via:
 * - `MaterialTheme.colorScheme` / `MaterialTheme.typography` for Material slots, or
 * - `TanukiTheme.colors` / `TanukiTheme.spacing` / `TanukiTheme.typography` for the brand extras.
 *
 * @param reducedMotion whether the OS "reduce motion" setting is on. Passed in rather than read here:
 *   the platform call lives in `:shared`, which depends on this module. See [LocalReducedMotion].
 */
@Composable
fun TanukiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    reducedMotion: Boolean = false,
    content: @Composable () -> Unit
) {
    val extendedColors = if (darkTheme) tanukiDarkExtendedColors else tanukiLightExtendedColors
    val colorScheme = if (darkTheme) tanukiDarkColorScheme() else tanukiLightColorScheme()
    CompositionLocalProvider(
        LocalTanukiColors provides extendedColors,
        LocalTanukiSpacing provides TanukiSpacing,
        LocalTanukiTypography provides TanukiTextStyles,
        LocalReducedMotion provides reducedMotion
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = tanukiTypography(),
            shapes = tanukiShapes,
            content = content
        )
    }
}

/** Convenience accessors for the brand-extended tokens, mirroring `MaterialTheme.*`. */
object TanukiTheme {
    val colors: TanukiColors
        @Composable get() = LocalTanukiColors.current

    val spacing: TanukiSpacing
        @Composable get() = LocalTanukiSpacing.current

    val typography: TanukiTextStyles
        @Composable get() = LocalTanukiTypography.current
}
/** The M3 shape scale on the design system's radii (xs 4, sm 8, md 12, lg 16, xl 28). */
private val tanukiShapes =
    Shapes(
        extraSmall = RoundedCornerShape(TanukiRadii.xs),
        small = TanukiRadii.chipShape,
        medium = TanukiRadii.fieldShape,
        large = TanukiRadii.cardShape,
        extraLarge = TanukiRadii.dialogShape,
    )
