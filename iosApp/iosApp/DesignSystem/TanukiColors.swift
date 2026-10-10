import SwiftUI

/// The Tanuki palette, iOS dialect: `Color.tk.surface`, `Color.tk.text2`, …
///
/// Values come from the design project's `tokens/colors.css` (the remote claude.ai/design project is the
/// source of truth) and live in `Assets.xcassets/Tanuki/` as colorsets with a light and a dark
/// appearance, so every token follows the theme by itself. Android's `TanukiPalette` holds the same
/// values.
///
/// Not here on purpose: the CSS glass *fills* and *highlights* (`--glass-fill`, `--glass-hilite`). They
/// only exist so the web specimens can imitate Liquid Glass; on iOS 26 a glass surface is the system's
/// `.glassEffect()`, not a tinted blur.
extension Color {
    enum tk {
        // Brand / accent: tanuki orange, used sparingly (active, unread, primary action).
        static let orange = Color("Tanuki/orange")
        static let orangeLight = Color("Tanuki/orangeLight")
        static let orangePress = Color("Tanuki/orangePress")
        static let orangeTint = Color("Tanuki/orangeTint")

        // Neutral canvas: background → surface → raised.
        static let bg = Color("Tanuki/bg")
        static let surface = Color("Tanuki/surface")
        static let surface2 = Color("Tanuki/surface2")
        static let surface3 = Color("Tanuki/surface3")

        // Text: primary → secondary → tertiary/disabled, and on an orange fill.
        static let text1 = Color("Tanuki/text1")
        static let text2 = Color("Tanuki/text2")
        static let text3 = Color("Tanuki/text3")
        static let onAccent = Color("Tanuki/onAccent")

        // Lines. The iOS dialect divides with `glassLine` (translucent), not Android's opaque `border`.
        static let border = Color("Tanuki/border")
        static let borderStrong = Color("Tanuki/borderStrong")
        static let glassLine = Color("Tanuki/glassLine")
        static let glassStroke = Color("Tanuki/glassStroke")

        // Status / semantic: muted, never neon.
        static let success = Color("Tanuki/success")
        static let warning = Color("Tanuki/warning")
        static let danger = Color("Tanuki/danger")
        static let info = Color("Tanuki/info")

        // Scrims: dialog backdrop, and the soft one behind reader chrome.
        static let scrim = Color("Tanuki/scrim")
        static let scrimSoft = Color("Tanuki/scrimSoft")
    }
}
