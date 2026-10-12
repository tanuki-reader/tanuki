package app.tanuki.platform

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
import android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView

/**
 * Keeps the system-bar icon colors readable against the current theme: light glyphs on a dark app,
 * dark glyphs on a light app. Because the app draws edge-to-edge (its content sits *behind* the status
 * bar), the platform default won't always contrast — this drives it explicitly from the resolved theme.
 *
 * Android updates the window's insets controller (status **and** navigation bars); iOS matches the host
 * controller's interface style so the adaptive status bar picks the right glyph color. Call once near the
 * app root with the same `darkTheme` value the theme uses.
 *
 * @param darkTheme whether the app is currently rendering its dark dialect.
 */
@Composable
fun SystemBarsEffect(darkTheme: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    val window = view.context.findActivity()?.window ?: return
    SideEffect {
        // "Light bars" = dark glyphs, for a light app; clear the bits for dark. Covers status + nav.
        val lightBars = APPEARANCE_LIGHT_STATUS_BARS or APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(if (darkTheme) 0 else lightBars, lightBars)
    }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
