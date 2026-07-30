package app.tanuki.core.source.net.cloudflare

import app.tanuki.core.source.TestLogger
import app.tanuki.core.source.net.cookie.SourceCookieJar
import app.tanuki.core.source.net.installSourcePlugins
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.get
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests the common half of the Cloudflare interceptor ([cloudflarePlugin]) against a [MockEngine] and a
 * scripted [FakeSolver] — no real WebView. What matters is the control flow the design promises: a normal
 * response is untouched, a challenge triggers exactly one solve + one replay carrying the clearance, an
 * unsolved challenge surfaces unchanged, and a solved host is replayed without solving again.
 */
class CloudflareInterceptorTest {

    @Test
    fun `passes a normal response through without solving`() = runTest {
        val solver = FakeSolver(CLEARANCE)
        val client = clientWith(solver) { respond("ok", HttpStatusCode.OK) }

        val response = client.get("https://example.org/data")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(0, solver.calls, "an unchallenged request must not open the solver")
    }

    @Test
    fun `solves a challenge and retries with the clearance cookie and user agent`() = runTest {
        val solver = FakeSolver(CLEARANCE)
        var retryCookie: String? = null
        var retryUserAgent: String? = null
        val client = clientWith(solver) { request ->
            val cookie = request.headers[HttpHeaders.Cookie]
            if (cookie?.contains("cf_clearance=TOKEN") == true) {
                retryCookie = cookie
                retryUserAgent = request.headers[HttpHeaders.UserAgent]
                respond("ok", HttpStatusCode.OK)
            } else {
                respondChallenge()
            }
        }

        val response = client.get("https://protected.example/x")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(1, solver.calls)
        assertEquals("https://protected.example/x", solver.lastChallenge?.url)
        assertTrue(retryCookie?.contains("cf_clearance=TOKEN") == true, "retry must carry the clearance cookie")
        assertEquals("TanukiUA/1.0", retryUserAgent, "retry must use the UA the clearance is bound to")
    }

    @Test
    fun `surfaces the challenge unchanged when the solver cannot solve`() = runTest {
        val solver = FakeSolver(result = null)
        val client = clientWith(solver) { respondChallenge() }

        val response = client.get("https://protected.example/x")

        assertEquals(HttpStatusCode.Forbidden, response.status, "an unsolved challenge is returned as-is")
        assertEquals(1, solver.calls, "it should attempt exactly one solve, not loop")
    }

    @Test
    fun `replays a stored clearance on later requests without solving again`() = runTest {
        val solver = FakeSolver(CLEARANCE)
        var challengesServed = 0
        val store = CloudflareCookieStore()
        val client = HttpClient(MockEngine) {
            expectSuccess = false
            install(cloudflarePlugin(solver, store, TestLogger))
            engine {
                addHandler { request ->
                    if (request.headers[HttpHeaders.Cookie]?.contains("cf_clearance=TOKEN") == true) {
                        respond("ok", HttpStatusCode.OK)
                    } else {
                        challengesServed++
                        respondChallenge()
                    }
                }
            }
        }

        client.get("https://protected.example/a") // first hit: challenged → solved
        client.get("https://protected.example/b") // same host: clearance attached up front, never challenged

        assertEquals(1, solver.calls, "the host should be solved once, then replayed")
        assertEquals(1, challengesServed, "only the first request should ever see a challenge")
    }

    @OptIn(ExperimentalCoroutinesApi::class) // advanceUntilIdle
    @Test
    fun `concurrent challenges for one host open only one solver`() = runTest {
        // The solver parks until released, so every request piles onto the challenge at once — the burst
        // the reader's page prefetch produces against a host nobody has solved yet.
        val released = CompletableDeferred<Unit>()
        val solver = FakeSolver(CLEARANCE, parkUntil = released)
        val client = clientWith(solver) { request ->
            if (request.headers[HttpHeaders.Cookie]?.contains("cf_clearance=TOKEN") == true) {
                respond("ok", HttpStatusCode.OK)
            } else {
                respondChallenge()
            }
        }

        val requests = List(4) { index -> async { client.get("https://protected.example/page-$index") } }
        advanceUntilIdle() // let all four reach the challenge and queue behind the gate
        released.complete(Unit)
        val responses = requests.awaitAll()

        assertTrue(responses.all { it.status == HttpStatusCode.OK }, "every request should end up served")
        assertEquals(1, solver.calls, "one browser engine for the host, not one per concurrent request")
    }

    /**
     * The clearance UA has to survive the user-agent plugin, which also writes that header. It hooks the
     * request pipeline while this one hooks `Send`, so the clearance is applied later and wins — the
     * property that keeps a `cf_clearance` (bound to the UA that solved it) replayable at all.
     */
    @Test
    fun `the clearance user agent overrides the configured one on a replay`() = runTest {
        val solver = FakeSolver(CLEARANCE)
        val agents = mutableListOf<String?>()
        val client = HttpClient(MockEngine) {
            expectSuccess = false
            installSourcePlugins(
                solver = solver,
                cookieStore = CloudflareCookieStore(),
                cookieJar = SourceCookieJar(),
                userAgent = { "Configured/9.9" },
                logger = TestLogger,
            )
            engine {
                addHandler { request ->
                    agents += request.headers[HttpHeaders.UserAgent]
                    if (request.headers[HttpHeaders.Cookie]?.contains("cf_clearance=TOKEN") == true) {
                        respond("ok", HttpStatusCode.OK)
                    } else {
                        respondChallenge()
                    }
                }
            }
        }

        val response = client.get("https://protected.example/x")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(
            listOf<String?>("Configured/9.9", "TanukiUA/1.0"),
            agents.toList(),
            "first attempt uses the configured UA; the replay uses the one the clearance is bound to",
        )
    }

    /**
     * A clearance is bound to the whole browser identity, not just the UA. Chromium sends user-agent client
     * hints on every request and Cloudflare lists them in `critical-ch`, so replaying the cookie with a
     * Chrome UA and no hints is a contradiction it re-challenges — which is exactly why Android failed
     * where iOS (WebKit, no client hints) succeeded.
     */
    @Test
    fun `replays the client hints the clearance was earned with`() = runTest {
        val hints = mapOf("Sec-CH-UA" to "\"Chromium\";v=\"150\"", "Sec-CH-UA-Mobile" to "?1")
        val solver = FakeSolver(CLEARANCE.copy(headers = hints))
        var replayHints: Map<String, String>? = null
        val client = clientWith(solver) { request ->
            if (request.headers[HttpHeaders.Cookie]?.contains("cf_clearance=TOKEN") == true) {
                replayHints = hints.keys.mapNotNull { name -> request.headers[name]?.let { name to it } }.toMap()
                respond("ok", HttpStatusCode.OK)
            } else {
                respondChallenge()
            }
        }

        client.get("https://protected.example/x")

        assertEquals(hints, replayHints, "every header the clearance carries must ride on the replay")
    }

    private fun clientWith(
        solver: CloudflareChallengeSolver,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): HttpClient =
        HttpClient(MockEngine) {
            expectSuccess = false
            install(cloudflarePlugin(solver, CloudflareCookieStore(), TestLogger))
            engine { addHandler(handler) }
        }

    /** A Cloudflare challenge response, shaped like the live MangaFire interstitial — see [isCloudflareChallenge]. */
    private fun MockRequestHandleScope.respondChallenge(): HttpResponseData =
        respond(
            content = "challenge",
            status = HttpStatusCode.Forbidden,
            headers = Headers.build {
                append(HttpHeaders.Server, "cloudflare")
                append("cf-ray", "abc123")
                append("cf-mitigated", "challenge")
                append("server-timing", "chlray;desc=\"abc123\"")
            },
        )

    private companion object {
        val CLEARANCE = CfClearance(cookies = mapOf("cf_clearance" to "TOKEN"), userAgent = "TanukiUA/1.0")
    }
}

/** A [CloudflareChallengeSolver] that returns a scripted [result] and records how it was called. When
 *  [parkUntil] is given the solve suspends on it, so a test can hold the gate open and let requests pile up. */
private class FakeSolver(
    private val result: CfClearance?,
    private val parkUntil: CompletableDeferred<Unit>? = null,
) : CloudflareChallengeSolver {
    var calls = 0
        private set
    var lastChallenge: CloudflareChallenge? = null
        private set

    override suspend fun solve(challenge: CloudflareChallenge): CfClearance? {
        calls++
        lastChallenge = challenge
        parkUntil?.await()
        return result
    }
}