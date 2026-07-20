package app.tanuki.platform

import androidx.compose.runtime.Composable

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
expect fun rememberReducedMotion(): Boolean