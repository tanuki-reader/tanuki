package app.tanuki.core.source.net.cookie

import app.tanuki.core.source.TestLogger
import app.tanuki.core.source.net.cloudflare.CfClearance
import app.tanuki.core.source.net.cloudflare.CloudflareChallenge
import app.tanuki.core.source.net.cloudflare.CloudflareChallengeSolver
import app.tanuki.core.source.net.cloudflare.CloudflareCookieStore
import app.tanuki.core.source.net.cloudflare.cloudflarePlugin
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests the source cookie jar ([sourceCookiePlugin]) against a [MockEngine]: that a site's `Set-Cookie`
 * is remembered and replayed per host, that a source's own `Cookie` header outranks the jar, and that
 * installing it alongside the Cloudflare plugin merges rather than clobbers — the behaviour Hipercool's
 * `401` retry and MangaLivre's proof-of-work cookie both depend on.
 */
class SourceCookiePluginTest {

    @Test
    fun `replays a cookie the site set on an earlier request`() = runTest {
        val sent = mutableListOf<String?>()
        val client = clientWith(SourceCookieJar()) { request ->
            sent += request.headers[HttpHeaders.Cookie]
            respond("ok", HttpStatusCode.OK, Headers.build { append(HttpHeaders.SetCookie, "session=abc; Path=/; HttpOnly") })
        }

        client.get("https://site.example/one")
        client.get("https://site.example/two")

        assertNull(sent[0], "nothing to send before the site has set anything")
        assertEquals("session=abc", sent[1], "the second request carries what the first was given")
    }

    @Test
    fun `keeps cookies scoped to the host that set them`() = runTest {
        val client = clientWith(SourceCookieJar()) { request ->
            val response =
                if (request.url.host == "a.example") {
                    Headers.build { append(HttpHeaders.SetCookie, "session=a") }
                } else {
                    Headers.Empty
                }
            respond(request.headers[HttpHeaders.Cookie] ?: "none", HttpStatusCode.OK, response)
        }

        client.get("https://a.example/x")
        val other = client.get("https://b.example/x")

        assertEquals("none", other.bodyAsText(), "a.example's cookie must not leak to b.example")
    }

    @Test
    fun `a source's own cookie header wins over the jar`() = runTest {
        val jar = SourceCookieJar()
        jar.put("site.example", mapOf("pow" to "stale", "session" to "kept"))
        var sent: String? = null
        val client = clientWith(jar) { request ->
            sent = request.headers[HttpHeaders.Cookie]
            respond("ok", HttpStatusCode.OK)
        }

        // What a guest does when it computes a cookie itself: sets the header on its own request.
        client.get("https://site.example/x") { header(HttpHeaders.Cookie, "pow=fresh") }

        val cookies = parseCookieHeader(sent.orEmpty())
        assertEquals("fresh", cookies["pow"], "the guest's value must not be overwritten by the jar's")
        assertEquals("kept", cookies["session"], "the jar's other cookies still ride along")
    }

    @Test
    fun `merges the clearance with the site session instead of replacing it`() = runTest {
        val jar = SourceCookieJar()
        val store = CloudflareCookieStore()
        var challenged = false
        var replayCookie: String? = null

        val client = HttpClient(MockEngine) {
            expectSuccess = false
            // Same order as the real client: Cloudflare first, so the jar ends up innermost and the
            // challenge replay passes back through it.
            install(cloudflarePlugin(StubSolver, store, TestLogger))
            install(sourceCookiePlugin(jar))
            engine {
                addHandler { request ->
                    val cookie = request.headers[HttpHeaders.Cookie]
                    if (cookie?.contains("cf_clearance=TOKEN") == true) {
                        replayCookie = cookie
                        respond("ok", HttpStatusCode.OK)
                    } else {
                        challenged = true
                        respond(
                            content = "challenge",
                            status = HttpStatusCode.Forbidden,
                            headers = Headers.build {
                                append(HttpHeaders.Server, "cloudflare")
                                append("cf-ray", "abc123")
                                // The marker that makes this a *challenge* and not an origin 403; without
                                // it the interceptor rightly passes the response straight through.
                                append("cf-mitigated", "challenge")
                                append(HttpHeaders.SetCookie, "session=live")
                            },
                        )
                    }
                }
            }
        }

        val response = client.get("https://protected.example/x")

        assertTrue(challenged, "the first attempt should be challenged")
        assertEquals(HttpStatusCode.OK, response.status)
        val cookies = parseCookieHeader(replayCookie.orEmpty())
        // Solving the challenge must not cost the site session — the bug a replacing header would cause.
        assertEquals("TOKEN", cookies["cf_clearance"])
        assertEquals("live", cookies["session"], "the session set by the challenge response survives the replay")
    }

    private fun clientWith(
        jar: SourceCookieJar,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): HttpClient =
        HttpClient(MockEngine) {
            expectSuccess = false
            install(sourceCookiePlugin(jar))
            engine { addHandler(handler) }
        }
}

/** A solver that always hands back the same clearance; the solving path itself is tested elsewhere. */
private object StubSolver : CloudflareChallengeSolver {
    override suspend fun solve(challenge: CloudflareChallenge): CfClearance =
        CfClearance(cookies = mapOf("cf_clearance" to "TOKEN"), userAgent = null)
}