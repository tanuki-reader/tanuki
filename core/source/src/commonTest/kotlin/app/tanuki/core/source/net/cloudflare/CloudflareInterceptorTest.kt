package app.tanuki.core.source.net.cloudflare

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
            install(cloudflarePlugin(solver, store))
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

    private fun clientWith(
        solver: CloudflareChallengeSolver,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): HttpClient =
        HttpClient(MockEngine) {
            expectSuccess = false
            install(cloudflarePlugin(solver, CloudflareCookieStore()))
            engine { addHandler(handler) }
        }

    /** A Cloudflare challenge response: a `403` served by Cloudflare with a `cf-ray`, per [isCloudflareChallenge]. */
    private fun MockRequestHandleScope.respondChallenge(): HttpResponseData =
        respond(
            content = "challenge",
            status = HttpStatusCode.Forbidden,
            headers = Headers.build {
                append(HttpHeaders.Server, "cloudflare")
                append("cf-ray", "abc123")
            },
        )

    private companion object {
        val CLEARANCE = CfClearance(cookies = mapOf("cf_clearance" to "TOKEN"), userAgent = "TanukiUA/1.0")
    }
}

/** A [CloudflareChallengeSolver] that returns a scripted [result] and records how it was called. */
private class FakeSolver(
    private val result: CfClearance?,
) : CloudflareChallengeSolver {
    var calls = 0
        private set
    var lastChallenge: CloudflareChallenge? = null
        private set

    override suspend fun solve(challenge: CloudflareChallenge): CfClearance? {
        calls++
        lastChallenge = challenge
        return result
    }
}