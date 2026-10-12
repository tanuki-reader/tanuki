package app.tanuki.assets.designSystem

import androidx.compose.ui.graphics.Color


/** The design system's fallback cover grounds, in design order (theme-invariant, as art is). */
private val coverFallbacks =
    with(TanukiNight) { listOf(CoverRust, CoverIndigo, CoverMoss, CoverPlum, CoverOchre, CoverTeal) }

/**
 * The fallback `cover-*` ground for a manga with no cover image (no URL, still loading, or failed),
 * picked by hashing [seed] (the manga id). iOS's `CoverSeed` ports the same `String.hashCode`, so a series
 * gets the same ground on both platforms.
 */
fun coverGround(seed: String): Color = coverFallbacks[(seed.hashCode() % coverFallbacks.size + coverFallbacks.size) % coverFallbacks.size]
