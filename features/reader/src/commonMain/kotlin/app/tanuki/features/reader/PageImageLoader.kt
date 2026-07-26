package app.tanuki.features.reader

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import coil3.ImageLoader

/**
 * The [ImageLoader] the reader fetches **page** images through — distinct from Coil's singleton loader,
 * which is tuned for covers (small, immutable 512px thumbnails). Pages are large, full-resolution
 * images with very different cache economics, so they get their own loader (bigger disk budget, a
 * memory cache sized for a few big bitmaps rather than a whole grid of thumbnails).
 *
 * It is a `staticCompositionLocalOf` because the loader is a process-lived singleton that never changes
 * during composition — the app builds it once and provides it at the root (see `App.kt`). Reading it
 * without a provider is a wiring bug, so the default throws rather than silently falling back to the
 * cover loader (which would defeat the point of a separate page cache).
 */
val LocalPageImageLoader: ProvidableCompositionLocal<ImageLoader> =
    staticCompositionLocalOf { error("No LocalPageImageLoader provided") }