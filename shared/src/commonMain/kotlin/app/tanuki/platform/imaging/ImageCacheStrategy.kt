package app.tanuki.platform.imaging

import coil3.annotation.ExperimentalCoilApi
import coil3.network.CacheStrategy
import coil3.network.CacheStrategy.ReadResult
import coil3.network.CacheStrategy.WriteResult
import coil3.network.NetworkRequest
import coil3.network.NetworkResponse
import coil3.request.Options

/**
 * The disk-cache policy both of the app's image loaders run with: **Coil's default for every image that
 * loaded, and nothing at all for one that didn't.**
 *
 * Coil 3's `DefaultCacheStrategy` never revalidates — its `read` always serves the disk entry, whatever
 * `Cache-Control`, `Expires` or `ETag` said — and that part is exactly right here: cover and page URLs
 * are content-addressed, so a stored image stays correct. The problem is its `write`, which stores
 * every 2xx *and* `300, 301, 404, 405, 410, 414, 501`. `NetworkFetcher` then finds that entry, the default
 * `read` hands it back, and the fetcher throws `HttpException` on it **without going to the network** —
 * so one `404` fails that cover or page from disk until the entry is LRU-evicted, hundreds of megabytes
 * of other images later. For the reader that also breaks the retry `PagePrefetch` relies on: a failed page
 * is forgotten so it can be warmed again, and every later attempt hit the stored `404` and failed at once.
 *
 * So failures are never written, and a failure already on disk (stored before this strategy shipped) is
 * read as a miss: `ReadResult(networkRequest)` is the hook Coil's fetcher leaves for exactly this, and the
 * next successful response overwrites the entry. Successes go to [CacheStrategy.DEFAULT] untouched, so
 * nothing that loads today behaves any differently.
 */
@OptIn(ExperimentalCoilApi::class)
internal object SuccessOnlyCacheStrategy : CacheStrategy {
    override suspend fun read(
        cacheResponse: NetworkResponse,
        networkRequest: NetworkRequest,
        options: Options,
    ): ReadResult =
        if (isCacheableImageResponse(cacheResponse.code)) {
            CacheStrategy.DEFAULT.read(cacheResponse, networkRequest, options)
        } else {
            ReadResult(networkRequest)
        }

    override suspend fun write(
        cacheResponse: NetworkResponse?,
        networkRequest: NetworkRequest,
        networkResponse: NetworkResponse,
        options: Options,
    ): WriteResult =
        if (isCacheableImageResponse(networkResponse.code)) {
            CacheStrategy.DEFAULT.write(cacheResponse, networkRequest, networkResponse, options)
        } else {
            WriteResult.DISABLED
        }
}

/**
 * Whether an image response may live in the disk cache: a success, or a `304` — which only refreshes the
 * headers of an entry that already succeeded (the default merges them). The loaders never send a
 * conditional request, so a `304` shouldn't arrive at all; it is kept so this stays correct if one does.
 */
internal fun isCacheableImageResponse(code: Int): Boolean = code in 200 until 300 || code == HTTP_NOT_MODIFIED

private const val HTTP_NOT_MODIFIED = 304
