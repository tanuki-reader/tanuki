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
 * The Bricolage Grotesque display face (bundled, OFL) at [weight], with its optical-size axis set to
 * [opticalSize] so each display style gets the cut drawn for its size.
 */
internal expect fun displayFontFamily(
    weight: FontWeight,
    opticalSize: Float,
): FontFamily

/**
 * The Tanuki type scale, Android dialect, from the design system's `tokens.json`.
 *
 * Display styles (`hero`, `titleXl`, `titleL`) are Bricolage Grotesque, always bold and tracked slightly
 * tight; never use them for body text, numbers or anything under 20sp. Text styles are the platform face
 * (Roboto, [FontFamily.Default]); `numeric` is mono, for counts and page numbers. Nothing is under 11sp.
 */
@Immutable
object TanukiTextStyles {
    private val text = FontFamily.Default
    private val mono = FontFamily.Monospace

    /** Series title on the manga detail hero; the onboarding headline. One per screen. */
    val hero =
        TextStyle(
            fontFamily = displayFontFamily(FontWeight.W700, 34f),
            fontWeight = FontWeight.W700,
            fontSize = 34.sp,
            lineHeight = 38.sp,
            letterSpacing = (-0.02).em,
        )

    /** Large screen titles (Library, Browse, …) when the bar is expanded. */
    val titleXl =
        TextStyle(
            fontFamily = displayFontFamily(FontWeight.W700, 28f),
            fontWeight = FontWeight.W700,
            fontSize = 28.sp,
            lineHeight = 32.sp,
            letterSpacing = (-0.015).em,
        )

    /** Dialog and sheet titles, empty-state headlines, section heroes. */
    val titleL =
        TextStyle(
            fontFamily = displayFontFamily(FontWeight.W600, 22f),
            fontWeight = FontWeight.W600,
            fontSize = 22.sp,
            lineHeight = 28.sp,
            letterSpacing = (-0.01).em,
        )

    /** Collapsed top-bar titles, row titles that lead a group, card titles. */
    val headline = TextStyle(fontFamily = text, fontWeight = FontWeight.W600, fontSize = 17.sp, lineHeight = 22.sp)

    /** Synopsis and long-form text on the detail screen. */
    val bodyLg = TextStyle(fontFamily = text, fontWeight = FontWeight.W400, fontSize = 16.sp, lineHeight = 24.sp)

    /** Default UI text: list-row titles, settings, menu items. */
    val body = TextStyle(fontFamily = text, fontWeight = FontWeight.W400, fontSize = 14.sp, lineHeight = 20.sp)

    /** Buttons, chips, tabs, segmented controls, text links. */
    val label =
        TextStyle(fontFamily = text, fontWeight = FontWeight.W600, fontSize = 13.sp, lineHeight = 16.sp, letterSpacing = 0.01.em)

    /** Metadata lines: chapter date, source name, file size. */
    val caption = TextStyle(fontFamily = text, fontWeight = FontWeight.W400, fontSize = 12.sp, lineHeight = 16.sp)

    /** Section headers above grouped lists. Set the text in uppercase. */
    val overline =
        TextStyle(fontFamily = text, fontWeight = FontWeight.W700, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.06.em)

    /** Series title on a cover tile, two lines max. */
    val tileTitle = TextStyle(fontFamily = text, fontWeight = FontWeight.W600, fontSize = 12.sp, lineHeight = 15.sp)

    /** Counts, progress and page numbers, in mono for a steady width. */
    val numeric = TextStyle(fontFamily = mono, fontWeight = FontWeight.W500, fontSize = 13.sp, lineHeight = 16.sp)
}

/** Material 3 [Typography] slots mapped per the design system, so M3 components come out on-brand. */
fun tanukiTypography(): Typography =
    with(TanukiTextStyles) {
        Typography(
            displaySmall = hero,
            headlineMedium = titleXl,
            headlineSmall = titleL,
            titleLarge = titleL,
            titleMedium = headline,
            bodyLarge = bodyLg,
            bodyMedium = body,
            bodySmall = caption,
            labelLarge = label,
            labelMedium = label,
            labelSmall = overline,
        )
    }

val LocalTanukiTypography = staticCompositionLocalOf { TanukiTextStyles }
