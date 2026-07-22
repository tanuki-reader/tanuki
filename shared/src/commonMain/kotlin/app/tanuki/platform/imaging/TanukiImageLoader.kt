package app.tanuki.platform.imaging

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.crossfade
import okio.Path

/**
 * Builds the app's single [ImageLoader] — the one-place cover fetching and caching are configured for
 * both platforms. Installed as Coil's singleton loader in `App.kt`.
 *
 * Covers are effectively immutable (their URLs embed a file hash), so both cache tiers are sized
 * generously and nothing needs revalidation: a cover fetched once is served from RAM within a session
 * and from disk across launches, and only genuine cache misses ever reach the source again.
 *
 *  - **Memory cache** — [MEMORY_CACHE_PERCENT] of app RAM; makes scrolling back up a grid instant.
 *  - **Disk cache** — up to [DISK_CACHE_MAX_BYTES] under a per-platform cache dir ([coverCacheDirectory]);
 *    survives process death and app restarts, LRU-evicted once the budget is exceeded.
 *
 * Fetches run over Ktor 3 so the image stack matches the source HTTP layer; crossfade softens the
 * gradient-placeholder → cover swap.
 *
 * TODO(cloudflare): rebuild on the shared, interceptor-carrying Ktor client once that lands, so cover
 *  fetches inherit `cf_clearance` the way source requests will (CLAUDE.md → "Cloudflare Bypass").
 */
fun newTanukiImageLoader(context: PlatformContext): ImageLoader =
    ImageLoader
        .Builder(context)
        .components { add(KtorNetworkFetcherFactory()) }
        .memoryCache {
            MemoryCache
                .Builder()
                .maxSizePercent(context, MEMORY_CACHE_PERCENT)
                .build()
        }.diskCache {
            DiskCache
                .Builder()
                .directory(coverCacheDirectory(context))
                .maxSizeBytes(DISK_CACHE_MAX_BYTES)
                .build()
        }.crossfade(true)
        .build()

/** Fraction of app memory the in-RAM cover cache may use (Coil's own default, made explicit). */
private const val MEMORY_CACHE_PERCENT = 0.25

/** Disk budget for cached covers, LRU-evicted past this. 256 MB holds a lot of 512px thumbnails. */
private const val DISK_CACHE_MAX_BYTES = 256L * 1024 * 1024

/**
 * The per-platform directory Coil writes cached cover files into — an OS *cache* location (Android
 * `cacheDir`, iOS `Library/Caches`) the system may reclaim under storage pressure. That's the right
 * home: covers are re-fetchable, unlike the library database, so losing them costs only a re-download.
 */
internal expect fun coverCacheDirectory(context: PlatformContext): Path