package app.tanuki.core.source.net.cloudflare

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests [isCloudflareChallenge] — the decision of whether to spend 30 seconds and a browser engine on a
 * response. The header shapes here are transcribed from live captures (a MangaFire challenge and ordinary
 * Cloudflare-proxied traffic), because the bug this pins was believing a header meant something it didn't.
 */
class CloudflareDetectionTest {

    @Test
    fun `detects the live challenge shape`() = runTest {
        assertTrue(
            isChallenge(
                HttpStatusCode.Forbidden,
                HttpHeaders.Server to "cloudflare",
                "cf-ray" to "a23192cabf715be2-LIS",
                "cf-mitigated" to "challenge",
                "server-timing" to "chlray;desc=\"a23192cabf715be2\"",
                HttpHeaders.ContentType to "text/html; charset=UTF-8",
            ),
        )
    }

    @Test
    fun `detects a challenge from cf-mitigated alone`() = runTest {
        assertTrue(isChallenge(HttpStatusCode.Forbidden, "cf-mitigated" to "challenge"))
    }

    @Test
    fun `detects a challenge from the challenge ray alone`() = runTest {
        assertTrue(isChallenge(HttpStatusCode.Forbidden, "server-timing" to "chlray;desc=\"abc\""))
    }

    @Test
    fun `detects a legacy under-attack challenge`() = runTest {
        assertTrue(isChallenge(HttpStatusCode.ServiceUnavailable, "cf-chl-bypass" to "1"))
    }

    /**
     * The regression this whole change is about: `cf-ray` and `server: cloudflare` ride on *every*
     * Cloudflare-proxied response, so an origin `403` — a bad VRF signature, a rejected key — carries them
     * too. Treating that as a challenge cost a 30s WebView spin before the real error surfaced.
     */
    @Test
    fun `an origin error behind cloudflare is not a challenge`() = runTest {
        assertFalse(
            isChallenge(
                HttpStatusCode.Forbidden,
                HttpHeaders.Server to "cloudflare",
                "cf-ray" to "a23192cabf715be2-LIS",
                HttpHeaders.ContentType to "application/json",
            ),
        )
    }

    /** A WAF block is final: Cloudflare served it, but there is nothing for a browser engine to answer. */
    @Test
    fun `a cloudflare block page without a challenge marker is not a challenge`() = runTest {
        assertFalse(
            isChallenge(
                HttpStatusCode.Forbidden,
                HttpHeaders.Server to "cloudflare",
                "cf-ray" to "a23192cabf715be2-LIS",
                "server-timing" to "cfL4;desc=\"?proto=TCP\"",
            ),
        )
    }

    @Test
    fun `a successful response is never a challenge`() = runTest {
        assertFalse(isChallenge(HttpStatusCode.OK, "cf-mitigated" to "challenge"))
    }

    @Test
    fun `matches chlray as a metric name rather than as substring of another entry`() = runTest {
        assertFalse(isChallenge(HttpStatusCode.Forbidden, "server-timing" to "cfExtPri;desc=\"chlray\""))
        assertTrue(isChallenge(HttpStatusCode.Forbidden, "server-timing" to "cfL4;desc=\"x\", chlray;desc=\"y\""))
    }

    /** Runs a canned response through a real client so the extension is exercised on an [HttpResponse]. */
    private suspend fun isChallenge(
        status: HttpStatusCode,
        vararg headers: Pair<String, String>,
    ): Boolean {
        val client = HttpClient(MockEngine) {
            expectSuccess = false
            engine {
                addHandler {
                    respond(
                        content = "body",
                        status = status,
                        headers = Headers.build { headers.forEach { (name, value) -> append(name, value) } },
                    )
                }
            }
        }
        return client.get("https://protected.example/x").isCloudflareChallenge()
    }
}
