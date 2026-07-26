package app.tanuki.platform.imaging

import coil3.PlatformContext
import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

/**
 * On iOS, Coil's [PlatformContext] carries no filesystem info, so resolve the app's `Library/Caches`
 * directory via Foundation. `NSSearchPathForDirectoriesInDomains` avoids the error-pointer dance of the
 * `NSFileManager` URL API and always returns the standard per-app caches path.
 */
internal actual fun coverCacheDirectory(context: PlatformContext): Path {
    val caches = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true)
        .first() as String
    return caches.toPath() / "cover_cache"
}

internal actual fun pageCacheDirectory(context: PlatformContext): Path {
    val caches = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true)
        .first() as String
    return caches.toPath() / "page_cache"
}