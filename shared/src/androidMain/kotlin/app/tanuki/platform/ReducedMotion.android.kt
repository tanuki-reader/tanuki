package app.tanuki.platform

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        // The accessibility "Remove animations" toggle drives every animation scale to 0. A value of 0
        // (system default is 1) is the standard signal that the user wants motion suppressed.
        val scale = Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )
        scale == 0f
    }
}