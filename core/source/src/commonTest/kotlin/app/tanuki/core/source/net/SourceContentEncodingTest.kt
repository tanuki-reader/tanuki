package app.tanuki.core.source.net

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
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests the `Accept-Encoding` policy [installSourcePlugins] puts on the shared source client, against a
 * [MockEngine].
 *
 * **Request-side only, on purpose.** Whether the *response* is decoded is platform-specific and cannot be
 * asserted here: Ktor's `ContentEncoding` decodes on the JVM but is a no-op decoder on Darwin, where
 * NSURLSession does the work instead — and this source set runs on both the Android host and the iOS
 * simulator, so a decode assertion would pass on one and fail on the other. The gzip round trip lives in
 * `androidHostTest` (`SourceContentEncodingDecodeTest`), where it is true.
 *
 * What *is* uniform is the header that goes out, and that is where the bug was: a source setting its own
 * `Accept-Encoding` used to reach the wire and defeat the engine's transparent gzip.
 */
class SourceContentEncodingTest {

    @Test
    fun `advertises every encoding the client can actually decode`() = runTest {
        val sent = mutableListOf<String?>()
        val client = clientWith { request ->
            sent += request.headers[HttpHeaders.AcceptEncoding]
            respond("ok", HttpStatusCode.OK)
        }

        client.get("https://example.org/data")

        // Pinned exactly, because the value is a decision and not an accident: gzip and deflate are all
        // Ktor 3.5 can decode (there is no brotli decoder), and identity is here so an explicit
        // `Content-Encoding: identity` doesn't fail over a body that needed nothing done to it.
        assertEquals(listOf<String?>("gzip,deflate,identity"), sent)
    }

    @Test
    fun `replaces an Accept-Encoding the caller set`() = runTest {
        // The bug: KtorSourceHost appends a wasm guest's headers verbatim, and KtorPageFetcher does the
        // same with a page's headers. Either could previously put `br` on the wire, and OkHttp then
        // declined to decode anything, handing the source raw compressed bytes.
        val sent = mutableListOf<List<String>>()
        val client = clientWith { request ->
            sent += request.headers.getAll(HttpHeaders.AcceptEncoding).orEmpty()
            respond("ok", HttpStatusCode.OK)
        }

        client.get("https://example.org/data") { header(HttpHeaders.AcceptEncoding, "br") }

        assertEquals(listOf(listOf("gzip,deflate,identity")), sent, "exactly one value, and it is ours")
    }

    @Test
    fun `strips the caller's header whatever case it is written in`() = runTest {
        val sent = mutableListOf<List<String>>()
        val client = clientWith { request ->
            sent += request.headers.getAll(HttpHeaders.AcceptEncoding).orEmpty()
            respond("ok", HttpStatusCode.OK)
        }

        client.get("https://example.org/data") { header("accept-encoding", "br") }

        assertEquals(listOf(listOf("gzip,deflate,identity")), sent)
    }

    @Test
    fun `the header survives a retry`() = runTest {
        // HttpRequestRetry copies the request builder per attempt rather than re-running the request
        // pipeline, so the second attempt carries whatever the first was given.
        val sent = mutableListOf<String?>()
        val client = clientWith { request ->
            sent += request.headers[HttpHeaders.AcceptEncoding]
            if (sent.size == 1) respond("busy", HttpStatusCode.ServiceUnavailable) else respond("ok", HttpStatusCode.OK)
        }

        client.get("https://example.org/data")

        assertEquals(listOf<String?>("gzip,deflate,identity", "gzip,deflate,identity"), sent)
    }

    @Test
    fun `the header survives a Cloudflare challenge replay`() = runTest {
        // Same shape, different mechanism: the interceptor replays inside `Send`, and the request
        // pipeline — where the header is set — does not run again.
        val sent = mutableListOf<String?>()
        val client = clientWith(EncodingSolver(CLEARANCE)) { request ->
            sent += request.headers[HttpHeaders.AcceptEncoding]
            if (sent.size == 1) respondChallenge() else respond("ok", HttpStatusCode.OK)
        }

        client.get("https://protected.example/x")

        assertEquals(listOf<String?>("gzip,deflate,identity", "gzip,deflate,identity"), sent)
    }

    private fun clientWith(
        solver: CloudflareChallengeSolver = EncodingSolver(null),
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): HttpClient =
        HttpClient(MockEngine) {
            installSourcePlugins(
                solver = solver,
                cookieStore = CloudflareCookieStore(),
                cookieJar = SourceCookieJar(),
                userAgent = { "Configured/9.9" },
                logger = TestLogger,
            )
            engine { addHandler(handler) }
        }

    /** A challenge response shaped like the live MangaFire interstitial. */
    private fun MockRequestHandleScope.respondChallenge(): HttpResponseData =
        respond(
            content = "challenge",
            status = HttpStatusCode.Forbidden,
            headers = Headers.build {
                append(HttpHeaders.Server, "cloudflare")
                append("cf-mitigated", "challenge")
            },
        )

    private companion object {
        val CLEARANCE = CfClearance(cookies = mapOf("cf_clearance" to "TOKEN"), userAgent = "TanukiUA/1.0")
    }
}

/**
 * A solver that hands back a scripted clearance. Named apart from `SourceHttpRetryTest`'s equivalent
 * because Kotlin/Native rejects two private top-level declarations sharing a name in one package.
 */
private class EncodingSolver(
    private val result: CfClearance?,
) : CloudflareChallengeSolver {
    override suspend fun solve(challenge: CloudflareChallenge): CfClearance? = result
}
