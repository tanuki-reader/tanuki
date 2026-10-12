package app.tanuki.platform.imaging

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.annotation.ExperimentalCoilApi
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.crossfade
import io.ktor.client.HttpClient
import okio.Path

/**
 * Builds the app's single [ImageLoader] — the one-place cover fetching and caching are configured for
 * both platforms. Installed as Coil's singleton loader in `App.kt`.
 *
 * Covers are effectively immutable (their URLs embed a file hash), so both cache tiers are sized
 * generously and nothing needs revalidation: a cover fetched once is served from RAM within a session
 * and from disk across launches, and only genuine cache misses ever reach the source again. What is
 * *not* kept is a failure — [SuccessOnlyCacheStrategy] stops Coil's default from storing a `404` and
 * then serving it from disk forever, so a cover that failed is asked for again next time.
 *
 * That immutability is also why **a cover is keyed on its URL alone** — `coverImageRequest` sets no
 * `memoryCacheKey` or `diskCacheKey`, so new art means a new key by construction. `MangaCover` carried a
 * cache-busting version column until schema v12 in case that assumption ever broke; it was deleted
 * rather than wired up, because it was only ever re-stamped when the URL had already changed, and
 * because the browse grid renders covers from a source listing with no local row to read a version from
 * — so keying on one would have cached the same image twice, once for browse and once for the library.
 * If a source is ever found that does mutate art behind a stable URL, the fix belongs here, as a key
 * transform on the request, not as a column no request consults.
 *
 *  - **Memory cache** — [MEMORY_CACHE_PERCENT] of app RAM; makes scrolling back up a grid instant.
 *  - **Disk cache** — up to [DISK_CACHE_MAX_BYTES] under a per-platform cache dir ([coverCacheDirectory]);
 *    survives process death and app restarts, LRU-evicted once the budget is exceeded.
 *
 * Fetches run over the app's **shared, Cloudflare-interceptor-carrying** Ktor client ([httpClient]) — the
 * same instance a source's API calls use — so a cover from a protected host inherits the `cf_clearance`
 * already solved for it and no cover fetch bypasses the interceptor (CLAUDE.md → "Cloudflare Bypass").
 * Crossfade softens the gradient-placeholder → cover swap.
 *
 * @param httpClient the shared source client; not owned here (a process-lived DI singleton), so this
 *   loader never closes it out from under the source layer.
 */
@OptIn(ExperimentalCoilApi::class) // KtorNetworkFetcherFactory(httpClient = …) and CacheStrategy are experimental.
internal fun newTanukiImageLoader(
    context: PlatformContext,
    httpClient: HttpClient,
): ImageLoader =
    ImageLoader
        .Builder(context)
        .components {
            add(KtorNetworkFetcherFactory(httpClient = { httpClient }, cacheStrategy = { SuccessOnlyCacheStrategy }))
        }
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
 * Builds the reader's **page** [ImageLoader] — deliberately separate from [newTanukiImageLoader].
 *
 * Covers and pages have opposite cache economics. A cover is a 512px thumbnail shown for as long as its
 * series is on screen, so the cover loader keeps a big *memory* cache (scrolling a grid re-reads them
 * constantly). A page is a large, full-resolution image the user pages past once, so the page loader
 * inverts the balance: a much larger *disk* budget (a whole chapter is tens of big images worth
 * keeping between sessions) and a **smaller** memory fraction, so a handful of decoded full-size
 * bitmaps can't crowd out the rest of the app. Sharing one loader would force one policy on both.
 *
 * Fetches run over the app's **shared, Cloudflare-interceptor-carrying** Ktor client ([httpClient]) — the
 * same instance source API calls use — so a page from a protected host (the reader's real Cloudflare
 * case) inherits the solved `cf_clearance` and no page fetch bypasses the interceptor.
 *
 * Same [SuccessOnlyCacheStrategy] as covers, and it matters more here: a failed page is re-warmed by
 * `PagePrefetch`, and with a `404` stored on disk every one of those retries would fail without a request.
 *
 * Crossfade is deliberately **off**, unlike the cover loader. A cover fades in behind a gradient placeholder,
 * where the transition hides a pop-in. A page arrives on a black surface and is the only thing on screen, so
 * the fade buys nothing visually and costs a full-screen composite of *two* page-sized bitmaps on every frame
 * it runs — paid once per page turn, which is exactly when the reader is already busy decoding the next page.
 *
 * Page slots are still sized from the decoded result rather than the painter's intrinsic size (see
 * `ReaderPage`); that no longer works around `CrossfadePainter`, but it remains the only reading that is
 * unambiguously the current page on a recycled item.
 *
 * @param httpClient the shared source client; not owned here (a process-lived DI singleton), so this
 *   loader never closes it out from under the source layer.
 */
@OptIn(ExperimentalCoilApi::class) // KtorNetworkFetcherFactory(httpClient = …) and CacheStrategy are experimental.
internal fun newTanukiPageImageLoader(
    context: PlatformContext,
    httpClient: HttpClient,
): ImageLoader =
    ImageLoader
        .Builder(context)
        .components {
            add(KtorNetworkFetcherFactory(httpClient = { httpClient }, cacheStrategy = { SuccessOnlyCacheStrategy }))
        }
        .memoryCache {
            MemoryCache
                .Builder()
                .maxSizePercent(context, PAGE_MEMORY_CACHE_PERCENT)
                .build()
        }.diskCache {
            DiskCache
                .Builder()
                .directory(pageCacheDirectory(context))
                .maxSizeBytes(PAGE_DISK_CACHE_MAX_BYTES)
                .build()
        }.build()

/**
 * Fraction of app memory the in-RAM page cache may use — lower than [MEMORY_CACHE_PERCENT] because a
 * full-resolution page bitmap is far larger than a thumbnail, so a few of them already fill the budget.
 * Prefetch (see the reader) keeps the *next* pages warm regardless; this only bounds how many stay
 * decoded in RAM at once.
 */
private const val PAGE_MEMORY_CACHE_PERCENT = 0.15

/** Disk budget for cached pages, LRU-evicted past this. Far larger than covers — pages are big and a
 *  read caches a whole chapter. */
private const val PAGE_DISK_CACHE_MAX_BYTES = 512L * 1024 * 1024

/**
 * The per-platform directory Coil writes cached cover files into — an OS *cache* location (Android
 * `cacheDir`, iOS `Library/Caches`) the system may reclaim under storage pressure. That's the right
 * home: covers are re-fetchable, unlike the library database, so losing them costs only a re-download.
 */
internal expect fun coverCacheDirectory(context: PlatformContext): Path

/**
 * The per-platform directory Coil writes cached **page** files into — kept separate from
 * [coverCacheDirectory] so the two caches are sized and evicted independently (see
 * [newTanukiPageImageLoader]). Same OS *cache* location rationale: pages are re-fetchable.
 */
internal expect fun pageCacheDirectory(context: PlatformContext): Path