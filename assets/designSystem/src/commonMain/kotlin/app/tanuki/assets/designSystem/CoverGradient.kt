package app.tanuki.assets.designSystem

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode

/**
 * Deterministic fallback gradient for a manga with no cover image (no URL, still loading, or the load
 * failed). Picked from a fixed palette by hashing [seed] (the manga id), so the same manga always gets
 * the same colors — the library tile and the detail hero agree on a series' "color" until real covers
 * load. Ported from the iOS `Manga_+CoverGradient` extension; kept standalone (not inlined into
 * [component.MangaCover]) precisely because more than one surface reuses it.
 */
private val coverGradientPalettes: List<List<Color>> =
    listOf(
        listOf(Color(0xFF7A1F12), Color(0xFFC8651A), Color(0xFFE0A33A)),
        listOf(Color(0xFF0E3A55), Color(0xFF1F7FB0), Color(0xFF4CC0D0)),
        listOf(Color(0xFF1E3A2A), Color(0xFF2F6F4A), Color(0xFF7FC090)),
        listOf(Color(0xFF2A1A45), Color(0xFF5B3BA0), Color(0xFFA07BE0)),
        listOf(Color(0xFF3A2E16), Color(0xFF8A6A2A), Color(0xFFD0B060)),
        listOf(Color(0xFF143A30), Color(0xFF2A8A6A), Color(0xFF70D0A0))
    )

/** The palette colors for [seed] — exposed so surfaces that don't want a [Brush] (e.g., a tinted
 *  backdrop) can read the same colors the cover uses. */
fun coverGradientColors(seed: String): List<Color> {
    // stable, platform-independent hash (kotlin's String.hashCode is spec'd), floored into the palette.
    val index =
        (seed.hashCode() % coverGradientPalettes.size + coverGradientPalettes.size) %
            coverGradientPalettes.size
    return coverGradientPalettes[index]
}

/** Top-leading → bottom-trailing linear gradient for [seed]. */
fun coverGradient(seed: String): Brush =
    Brush.linearGradient(
        colors = coverGradientColors(seed),
        start = Offset(500f, 0f),
        end = Offset.Infinite
    )