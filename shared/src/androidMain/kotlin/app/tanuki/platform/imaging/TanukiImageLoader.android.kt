package app.tanuki.platform.imaging

import coil3.PlatformContext
import okio.Path
import okio.Path.Companion.toPath

/**
 * On Android, Coil's [PlatformContext] *is* the `android.content.Context`, so the OS cache directory is
 * available directly — no DI needed here.
 */
internal actual fun coverCacheDirectory(context: PlatformContext): Path =
    context.cacheDir
        .resolve("cover_cache")
        .absolutePath
        .toPath()

internal actual fun pageCacheDirectory(context: PlatformContext): Path =
    context.cacheDir
        .resolve("page_cache")
        .absolutePath
        .toPath()