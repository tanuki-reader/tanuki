package app.tanuki.platform.imaging

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.annotation.ExperimentalCoilApi
import coil3.disk.DiskCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The bridge against a loader configured like [newTanukiImageLoader] (Ktor fetcher, success-only
 * caching, a disk cache), over a fake network and a throwaway cache directory.
 */
@OptIn(ExperimentalCoilApi::class)
class ImageBridgeTest {
    private val cover = byteArrayOf(1, 2, 3, 4, 5)
    private var requests = 0
    private var lastReferer: String? = null
    private var status = HttpStatusCode.OK

    private val loader: ImageLoader = run {
        val client =
            HttpClient(
                MockEngine { request ->
                    requests++
                    lastReferer = request.headers[HttpHeaders.Referrer]
                    respond(cover, status, headersOf(HttpHeaders.ContentType, "image/jpeg"))
                }
            )
        ImageLoader
            .Builder(PlatformContext.INSTANCE)
            .components {
                add(KtorNetworkFetcherFactory(httpClient = { client }, cacheStrategy = { SuccessOnlyCacheStrategy }))
            }.diskCache {
                DiskCache
                    .Builder()
                    .directory(FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "image-bridge-test-${Random.nextLong()}")
                    .build()
            }.build()
    }

    @Test
    fun returnsTheEncodedBytesAndSendsTheHeaders() =
        runTest {
            val bytes = loadEncodedImage(loader, URL, mapOf(HttpHeaders.Referrer to "https://source.test/"))
            assertContentEquals(cover, bytes)
            assertEquals("https://source.test/", lastReferer)
        }

    @Test
    fun secondLoadIsServedFromTheDiskCache() =
        runTest {
            loadEncodedImage(loader, URL, emptyMap())
            assertContentEquals(cover, loadEncodedImage(loader, URL, emptyMap()))
            assertEquals(1, requests)
        }

    @Test
    fun failureIsNullAndNotCached() =
        runTest {
            status = HttpStatusCode.NotFound
            assertNull(loadEncodedImage(loader, URL, emptyMap()))
            status = HttpStatusCode.OK
            assertContentEquals(cover, loadEncodedImage(loader, URL, emptyMap()))
            assertEquals(2, requests)
        }

    private companion object {
        const val URL = "https://covers.test/a.jpg"
    }
}
