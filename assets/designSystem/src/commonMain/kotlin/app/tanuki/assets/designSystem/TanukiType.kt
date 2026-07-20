package app.tanuki.assets.designSystem

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Tanuki typography tokens — Android dialect, synced from `tokens/typography.css`.
 *
 * The Android face is **Roboto**, which is the platform default — so [FontFamily.Default] resolves to
 * it with no font bundling. (iOS uses SF Pro via the system stack in its own SwiftUI token set.)
 * Scale from the design doc: Display 28 / Title 20 / Headline 17 / Body 14 / Caption 11 / Label 9.
 */
@Immutable
object TanukiTextStyles {
    private val ui = FontFamily.Default // Roboto on Android
    private val mono = FontFamily.Monospace // Roboto Mono on Android

    private val Regular = FontWeight.W400
    private val Medium = FontWeight.W500
    private val Bold = FontWeight.W700

    val display =
        TextStyle(
            fontFamily = ui,
            fontWeight = Bold,
            fontSize = 28.sp,
            lineHeight = 34.sp,
            letterSpacing = (-0.02).em
        )
    val title =
        TextStyle(
            fontFamily = ui,
            fontWeight = Bold,
            fontSize = 20.sp,
            lineHeight = 26.sp,
            letterSpacing = (-0.02).em
        )
    val headline =
        TextStyle(
            fontFamily = ui,
            fontWeight = Medium,
            fontSize = 17.sp,
            lineHeight = 22.sp
        )
    val body =
        TextStyle(
            fontFamily = ui,
            fontWeight = Regular,
            fontSize = 14.sp,
            lineHeight = 20.sp
        )
    val caption =
        TextStyle(
            fontFamily = ui,
            fontWeight = Regular,
            fontSize = 11.sp,
            lineHeight = 15.sp
        )
    val label =
        TextStyle(
            fontFamily = ui,
            fontWeight = Medium,
            fontSize = 9.sp,
            lineHeight = 12.sp,
            letterSpacing = 0.04.em
        )
    val mono14 =
        TextStyle(
            fontFamily = mono,
            fontWeight = Regular,
            fontSize = 14.sp,
            lineHeight = 20.sp
        )
}

/**
 * Material 3 [Typography] mapped from the Tanuki scale, so components reading
 * `MaterialTheme.typography` get on-brand styles. The full named set (incl. caption/label/mono) is on
 * [TanukiTextStyles], reached via [LocalTanukiTypography].
 */
fun tanukiTypography(): Typography =
    Typography(
        displayLarge = TanukiTextStyles.display,
        titleLarge = TanukiTextStyles.title,
        titleMedium = TanukiTextStyles.headline,
        bodyMedium = TanukiTextStyles.body,
        bodySmall = TanukiTextStyles.caption,
        labelSmall = TanukiTextStyles.label
    )

val LocalTanukiTypography = staticCompositionLocalOf { TanukiTextStyles }