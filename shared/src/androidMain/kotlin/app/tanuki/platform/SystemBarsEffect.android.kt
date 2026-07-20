package app.tanuki.platform

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
import android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView

@Composable
actual fun SystemBarsEffect(darkTheme: Boolean) {
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
