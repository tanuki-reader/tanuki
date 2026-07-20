package app.tanuki.platform

import androidx.compose.runtime.Composable

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
expect fun SystemBarsEffect(darkTheme: Boolean)
