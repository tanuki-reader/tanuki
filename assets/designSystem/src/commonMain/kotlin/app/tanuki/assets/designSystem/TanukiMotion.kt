package app.tanuki.assets.designSystem

import androidx.compose.animation.core.EaseInCubic
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.Easing
import androidx.compose.runtime.Immutable

/**
 * Tanuki motion tokens — durations and easings for screen-level transitions, both platforms.
 *
 * Values follow the platform motion guidance the app is held to: the 150–300ms micro-interaction band,
 * with the exit snappier than the enter so a push feels responsive, and asymmetric easing (decelerate on
 * enter, accelerate on exit) so motion never reads as linear/robotic.
 *
 * The remote Tanuki Design System does not (yet) ship a `tokens/motion.css`; until it does, this object is
 * the local source of truth for transition motion and should be synced *back* to the design project when a
 * motion token set lands there. Do not inline raw millisecond/easing literals in screens — pull them here.
 */
@Immutable
object TanukiMotion {
    /** Enter/appear: content arriving decelerates into place. */
    const val screenEnterMillis: Int = 250

    /** Exit/dismiss: content leaving accelerates away — deliberately shorter than the enter. */
    const val screenExitMillis: Int = 200

    /**
     * Reduced-motion duration: a single quick crossfade with no spatial travel, used when the OS
     * "reduce motion" / "remove animations" accessibility setting is on. Not zero — an instant cut is
     * jarring; a short fade keeps the transition legible without conveying movement.
     */
    const val reducedMillis: Int = 100

    /** Decelerate — for entering content. */
    val EaseEnter: Easing = EaseOutCubic

    /** Accelerate — for exiting content. */
    val EaseExit: Easing = EaseInCubic
}
