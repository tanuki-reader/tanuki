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
import io.ktor.client.plugins.retry
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests the retry policy [installSourcePlugins] puts on the shared source client, against a [MockEngine].
 * `runTest` runs the backoff on virtual time, so the exponential delay costs nothing here.
 *
 * The cases that matter are the boundaries, not the happy path: what is *not* retried is the whole point.
 * A Cloudflare challenge in particular reaches the retry predicate as an ordinary 403/503 (the client sets
 * `expectSuccess = false`), and retrying it would fight the interceptor that is already handling it.
 */
class SourceHttpRetryTest {

    @Test
    fun `retries a transient server error and returns the attempt that succeeds`() = runTest {
        val attempts = mutableListOf<String>()
        val client = clientWith { request ->
            attempts += request.url.encodedPath
            if (attempts.size == 1) respond("busy", HttpStatusCode.ServiceUnavailable) else respond("ok", HttpStatusCode.OK)
        }

        val response = client.get("https://example.org/data")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(2, attempts.size, "the 503 should have been tried again")
    }

    @Test
    fun `gives up after the retry budget and returns the last response`() = runTest {
        var attempts = 0
        val client = clientWith {
            attempts++
            respond("down", HttpStatusCode.BadGateway)
        }

        val response = client.get("https://example.org/data")

        assertEquals(HttpStatusCode.BadGateway, response.status, "the caller sees the real failure, not an exception")
        assertEquals(3, attempts, "one attempt plus the two-retry budget")
    }

    @Test
    fun `does not retry a Cloudflare challenge`() = runTest {
        // The interceptor makes exactly two attempts of its own (challenge, then replay with the
        // clearance). Any attempt beyond those would be the retry plugin second-guessing it.
        val solver = ScriptedSolver(CLEARANCE)
        var attempts = 0
        val client = clientWith(solver) {
            attempts++
            respondChallenge()
        }

        val response = client.get("https://protected.example/x")

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals(2, attempts, "challenge plus one replay, and nothing else")
        assertEquals(1, solver.calls, "a retry would have opened the solver again")
    }

    @Test
    fun `does not retry a status that will answer the same way`() = runTest {
        var attempts = 0
        val client = clientWith {
            attempts++
            respond("gone", HttpStatusCode.NotFound)
        }

        val response = client.get("https://example.org/missing")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals(1, attempts, "a 404 is final")
    }

    @Test
    fun `does not replay a request that is not idempotent`() = runTest {
        var attempts = 0
        val client = clientWith {
            attempts++
            respond("busy", HttpStatusCode.ServiceUnavailable)
        }

        client.post("https://example.org/submit")

        assertEquals(1, attempts, "whether a POST may be replayed is the source's call, not ours")
    }

    @Test
    fun `retries a transport failure`() = runTest {
        var attempts = 0
        val client = clientWith {
            attempts++
            if (attempts == 1) throw IllegalStateException("connection reset") else respond("ok", HttpStatusCode.OK)
        }

        val response = client.get("https://example.org/data")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(2, attempts)
    }

    @Test
    fun `honours a per request opt out of retries`() = runTest {
        // The escape hatch KtorPageFetcher uses, so DownloadManager's own retry budget is not multiplied.
        var attempts = 0
        val client = clientWith {
            attempts++
            respond("busy", HttpStatusCode.ServiceUnavailable)
        }

        client.get("https://example.org/page") { retry { maxRetries = 0 } }

        assertEquals(1, attempts)
    }

    private fun clientWith(
        solver: CloudflareChallengeSolver = ScriptedSolver(null),
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

/** A solver that hands back a scripted clearance and counts how often it was opened. */
private class ScriptedSolver(
    private val result: CfClearance?,
) : CloudflareChallengeSolver {
    var calls = 0
        private set

    override suspend fun solve(challenge: CloudflareChallenge): CfClearance? {
        calls++
        return result
    }
}
