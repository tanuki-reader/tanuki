package app.tanuki.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled
import platform.UIKit.UIAccessibilityReduceMotionStatusDidChangeNotification
import platform.Foundation.NSNotificationCenter
import platform.darwin.NSObjectProtocol

@Composable
actual fun rememberReducedMotion(): Boolean {
    var reduced by remember { mutableStateOf(UIAccessibilityIsReduceMotionEnabled()) }
    // The user can flip "Reduce Motion" in Settings while the app is running; observe the change so the
    // next transition honors it without needing an app restart.
    DisposableEffect(Unit) {
        val center = NSNotificationCenter.defaultCenter
        val observer: NSObjectProtocol = center.addObserverForName(
            name = UIAccessibilityReduceMotionStatusDidChangeNotification,
            `object` = null,
            queue = null,
        ) { _ ->
            reduced = UIAccessibilityIsReduceMotionEnabled()
        }
        onDispose { center.removeObserver(observer) }
    }
    return reduced
}