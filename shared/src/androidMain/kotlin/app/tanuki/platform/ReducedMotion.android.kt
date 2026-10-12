package app.tanuki.platform

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Whether the OS "reduce motion" / "remove animations" accessibility preference is enabled.
 *
 * Screens use this to fall back to a plain crossfade instead of spatial (slide) transitions — respecting
 * a HIGH-severity accessibility rule that motion must honor the user's reduced-motion setting. Read once
 * near where transitions are configured (e.g. the NavHost) and branch the animation on the result.
 *
 * Platform binding: Android reads the animator duration scale (the "Remove animations" toggle drives it to
 * zero); iOS reads `UIAccessibility.isReduceMotionEnabled`.
 */
@Composable
fun rememberReducedMotion(): Boolean {
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