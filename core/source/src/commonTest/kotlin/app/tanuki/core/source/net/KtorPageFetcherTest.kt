package app.tanuki.core.source.net

import app.tanuki.core.domain.common.model.Url
import app.tanuki.core.domain.common.service.ConnectionObserver
import app.tanuki.core.domain.common.service.ConnectionObserver.ConnectionStatus
import app.tanuki.core.domain.download.model.DownloadError
import app.tanuki.core.domain.download.service.PageFetchException
import app.tanuki.core.domain.source.model.Page
import app.tanuki.core.source.FakeConnectionObserver
import app.tanuki.core.source.TestLogger
import app.tanuki.core.source.net.cloudflare.CfClearance
import app.tanuki.core.source.net.cloudflare.CloudflareChallenge
import app.tanuki.core.source.net.cloudflare.CloudflareChallengeSolver
import app.tanuki.core.source.net.cloudflare.CloudflareCookieStore
import app.tanuki.core.source.net.cookie.SourceCookieJar
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Tests the fetcher against a [MockEngine]. Its two jobs are both about what the engine downstream can
 * see: it hands back the untouched bytes, and it turns every way an HTTP request can go wrong into a
 * [DownloadError] — because the code that has to decide "retry, or tell the user to solve a challenge"
 * lives in `:core:domain` and cannot see a status code.
 */
class KtorPageFetcherTest {
    @Test
    fun `returns the bytes untouched with the extension the content type implies`() =
        runTest {
            val fetcher = fetcherReturning { respondImage("image/webp", byteArrayOf(1, 2, 3)) }

            val fetched = fetcher.fetch(page())

            assertContentEquals(byteArrayOf(1, 2, 3), fetched.bytes)
            assertEquals("webp", fetched.extension)
        }

    @Test
    fun `normalizes jpeg to the extension the file will actually carry`() =
        runTest {
            val fetcher = fetcherReturning { respondImage("image/jpeg", byteArrayOf(1)) }

            assertEquals("jpg", fetcher.fetch(page()).extension)
        }

    @Test
    fun `falls back to the URL extension when the response does not say what it sent`() =
        runTest {
            val fetcher = fetcherReturning { respond(byteArrayOf(1), HttpStatusCode.OK) }

            assertEquals("png", fetcher.fetch(page(url = "https://cdn.example/a/7.png?token=abc")).extension)
        }

    @Test
    fun `falls back to a generic extension when neither the response nor the URL says`() =
        runTest {
            val fetcher = fetcherReturning { respond(byteArrayOf(1), HttpStatusCode.OK) }

            assertEquals("img", fetcher.fetch(page(url = "https://cdn.example/data/7")).extension)
        }

    @Test
    fun `sends the per-request headers the source asked for`() =
        runTest {
            var referer: String? = null
            val fetcher =
                fetcherReturning { request ->
                    referer = request.headers["Referer"]
                    respondImage("image/jpeg", byteArrayOf(1))
                }

            // Hotlink-guarded sources serve a placeholder without this, which would download silently
            // and read as a broken chapter offline.
            fetcher.fetch(page().copy(headers = mapOf("Referer" to "https://mangafire.to/")))

            assertEquals("https://mangafire.to/", referer)
        }

    @Test
    fun `a Cloudflare challenge is reported as needing verification rather than as a refusal`() =
        runTest {
            val fetcher = fetcherReturning { respondChallenge() }

            val error = assertFailsWith<PageFetchException> { fetcher.fetch(page()) }

            // A challenge and an origin 403 are the same status and opposite situations: one is answerable
            // by a browser the queue has no window for, the other is final.
            assertEquals(DownloadError.NeedsVerification, error.error)
        }

    @Test
    fun `a rate limit or a server fault is reported as a network error worth retrying`() =
        runTest {
            assertEquals(
                DownloadError.Network,
                assertFailsWith<PageFetchException> {
                    fetcherReturning { respondError(HttpStatusCode.TooManyRequests) }.fetch(page())
                }.error,
            )
            assertEquals(
                DownloadError.Network,
                assertFailsWith<PageFetchException> {
                    fetcherReturning { respondError(HttpStatusCode.BadGateway) }.fetch(page())
                }.error,
            )
        }

    @Test
    fun `leaves retrying a page to the download queue rather than the client`() =
        runTest {
            var attempts = 0
            val fetcher =
                fetcherReturning {
                    attempts++
                    respondError(HttpStatusCode.ServiceUnavailable)
                }

            assertFailsWith<PageFetchException> { fetcher.fetch(page()) }

            // The client retries a 503 twice for everyone else. DownloadManager already gives a page
            // three attempts with its own backoff, and stacking the two would silently make it nine.
            assertEquals(1, attempts)
        }

    @Test
    fun `a delisted page is not retried into the ground`() =
        runTest {
            val fetcher = fetcherReturning { respondError(HttpStatusCode.NotFound) }

            // Anything but a rate limit or a server fault answers the same way three times over.
            assertEquals(
                DownloadError.Unknown,
                assertFailsWith<PageFetchException> { fetcher.fetch(page()) }.error,
            )
        }

    @Test
    fun `a request that never reaches the host is a network error`() =
        runTest {
            val fetcher = fetcherReturning { throw IOException("no route to host") }

            assertEquals(
                DownloadError.Network,
                assertFailsWith<PageFetchException> { fetcher.fetch(page()) }.error,
            )
        }

    @Test
    fun `an empty body is a failure rather than a zero-byte page`() =
        runTest {
            // Some CDNs answer 200 with nothing when a signed URL has expired. Committing that would
            // produce a chapter that is "downloaded" and blank.
            val fetcher = fetcherReturning { respondImage("image/jpeg", ByteArray(0)) }

            assertFailsWith<PageFetchException> { fetcher.fetch(page()) }
        }

    @Test
    fun `a page with no URL at all fails without a request`() =
        runTest {
            var requests = 0
            val fetcher =
                fetcherReturning {
                    requests++
                    respondImage("image/jpeg", byteArrayOf(1))
                }

            val error =
                assertFailsWith<PageFetchException> {
                    fetcher.fetch(Page(index = 0u, imageUrl = null, url = null))
                }

            assertEquals(DownloadError.SourceUnavailable, error.error)
            assertEquals(0, requests)
        }

    @Test
    fun `an offline device fails the page as a network error without a request`() =
        runTest {
            var requests = 0
            val fetcher =
                fetcherReturning(FakeConnectionObserver(ConnectionStatus.OFFLINE)) {
                    requests++
                    respondImage("image/jpeg", byteArrayOf(1))
                }

            val error = assertFailsWith<PageFetchException> { fetcher.fetch(page()) }

            // Network, not Unknown: the queue may retry this one — the user plausibly reconnects.
            assertEquals(DownloadError.Network, error.error)
            assertEquals(0, requests)
        }

    @Test
    fun `a constrained connection is still a connection`() =
        runTest {
            // LOW_SPEED must not read as offline anywhere: refusing to download on a slow link is a
            // policy decision, and it isn't the fetcher's to make.
            val fetcher =
                fetcherReturning(FakeConnectionObserver(ConnectionStatus.LOW_SPEED)) {
                    respondImage("image/jpeg", byteArrayOf(1, 2))
                }

            assertContentEquals(byteArrayOf(1, 2), fetcher.fetch(page()).bytes)
        }

    private fun page(url: String = "https://cdn.example/chapter/0.jpg") =
        Page(index = 0u, imageUrl = Url(url), url = null)

    /**
     * Built on the **real** client configuration ([installSourcePlugins]), not a bare one: the fetcher opts
     * out of the client's retry policy per request, and that opt-out is only meaningful — or testable —
     * against a client that actually has the policy installed.
     */
    private fun fetcherReturning(
        connection: ConnectionObserver = FakeConnectionObserver(),
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): KtorPageFetcher =
        KtorPageFetcher(
            HttpClient(MockEngine) {
                installSourcePlugins(
                    solver = UnsolvableSolver,
                    cookieStore = CloudflareCookieStore(),
                    cookieJar = SourceCookieJar(),
                    userAgent = { "Configured/9.9" },
                    logger = TestLogger,
                )
                engine { addHandler(handler) }
            },
            connection,
        )

    private fun MockRequestHandleScope.respondImage(
        contentType: String,
        bytes: ByteArray,
    ): HttpResponseData =
        respond(
            content = bytes,
            status = HttpStatusCode.OK,
            headers = Headers.build { append(HttpHeaders.ContentType, contentType) },
        )

    /** A challenge response shaped like the live MangaFire interstitial — see `isCloudflareChallenge`. */
    private fun MockRequestHandleScope.respondChallenge(): HttpResponseData =
        respond(
            content = "challenge",
            status = HttpStatusCode.Forbidden,
            headers =
                Headers.build {
                    append(HttpHeaders.Server, "cloudflare")
                    append("cf-mitigated", "challenge")
                    append("server-timing", "chlray;desc=\"abc123\"")
                },
        )
}

/** A solver that never solves — these tests are about classification, not about the bypass. */
private object UnsolvableSolver : CloudflareChallengeSolver {
    override suspend fun solve(challenge: CloudflareChallenge): CfClearance? = null
}