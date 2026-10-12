package app.tanuki.platform.imaging

import coil3.ImageLoader
import coil3.PlatformContext
import io.ktor.client.HttpClient
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@OptIn(ExperimentalAtomicApi::class)
private val pageImageLoader = AtomicReference<ImageLoader?>(null)

/**
 * The reader's page loader, one per process (Android's `App`, iOS's `ImageBridge`), so there is only
 * ever one `DiskCache` over `page_cache` — two over one directory would corrupt each other.
 *
 * Atomic because Swift asks for it from whatever thread its task runs on. Two first callers racing may
 * each build one; only the first is kept, and the loser is dropped before it has opened its cache (Coil
 * opens the disk cache lazily, on the first request). Holding it process-wide doesn't leak an Android
 * Activity: Coil's `ImageLoader.Builder` keeps only the application context.
 */
@OptIn(ExperimentalAtomicApi::class)
internal fun sharedPageImageLoader(
    context: PlatformContext,
    httpClient: HttpClient
): ImageLoader {
    pageImageLoader.load()?.let { return it }
    val built = newTanukiPageImageLoader(context, httpClient)
    return if (pageImageLoader.compareAndSet(null, built)) built else pageImageLoader.load()!!
}
