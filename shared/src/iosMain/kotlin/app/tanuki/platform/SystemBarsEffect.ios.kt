package app.tanuki.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.uikit.LocalUIViewController
import platform.UIKit.UIUserInterfaceStyle.UIUserInterfaceStyleDark
import platform.UIKit.UIUserInterfaceStyle.UIUserInterfaceStyleLight

/**
 * Forces the hosting view controller's interface style to match the app theme. The status bar uses the
 * adaptive `.default` style, which resolves its glyph color from that trait — so a dark app yields light
 * status-bar glyphs and a light app yields dark ones, regardless of the device's system setting.
 */
@Composable
actual fun SystemBarsEffect(darkTheme: Boolean) {
    val viewController = LocalUIViewController.current
    LaunchedEffect(darkTheme, viewController) {
        viewController.overrideUserInterfaceStyle =
            if (darkTheme) UIUserInterfaceStyleDark else UIUserInterfaceStyleLight
        viewController.setNeedsStatusBarAppearanceUpdate()
    }
}
