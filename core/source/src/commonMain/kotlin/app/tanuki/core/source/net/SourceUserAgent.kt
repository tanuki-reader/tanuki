package app.tanuki.core.source.net

import io.ktor.client.plugins.api.ClientPlugin
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.HttpHeaders

/**
 * The `User-Agent` every source request goes out with, unless a solved Cloudflare clearance overrides it
 * for that host (see [app.tanuki.core.source.net.cloudflare.cloudflarePlugin]).
 *
 * It has to be a **real browser** string. Cloudflare decides whether to challenge largely on the UA, and a
 * bot-shaped one (`Tanuki (+https://tanuki.app)`, the per-source constants this replaced) gets challenged on
 * the first request and then can never clear it: Turnstile cross-checks the UA against the JS environment,
 * so a browser engine claiming to be `Tanuki` is a contradiction it will spin on until the solve times out.
 *
 * Deliberately a **desktop** Chrome string, and the same one on both platforms: it is what Mihon ships, our
 * sources talk to JSON APIs where mobile/desktop makes no difference, and one string keeps a `cf_clearance`
 * — which is bound to the exact UA — reproducible across targets.
 *
 * Bump it occasionally; a Chrome version that is years stale is itself a bot signal.
 */
const val DEFAULT_SOURCE_USER_AGENT: String =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/131.0.0.0 Safari/537.36"

/**
 * Supplies the `User-Agent` for source traffic. The seam exists so the string can later come from user
 * settings (some hosts only serve certain agents, and a user behind a strict WAF may need their own)
 * without any other part of the stack knowing where it came from.
 *
 * `suspend` on purpose: today's [DefaultSourceUserAgentProvider] answers from a constant, but a
 * settings-backed one reads DataStore, and widening a non-suspend interface later would touch every caller.
 * It is consulted **per request**, so a changed setting takes effect on the next call with no client rebuild.
 *
 * Implementations must be cheap and must not throw — this runs on every source request.
 */
fun interface SourceUserAgentProvider {
    suspend fun userAgent(): String
}

/** The built-in provider: always [DEFAULT_SOURCE_USER_AGENT]. Swap the DI binding to make it configurable. */
object DefaultSourceUserAgentProvider : SourceUserAgentProvider {
    override suspend fun userAgent(): String = DEFAULT_SOURCE_USER_AGENT
}

/**
 * Stamps [provider]'s `User-Agent` onto every request on the shared source client, **replacing** whatever
 * the caller set. Sources don't get to choose their own UA: the app's Cloudflare posture depends on the
 * one string, and a source picking its own is how MangaFire ended up permanently challenged.
 *
 * Runs in the request pipeline (`onRequest`), which is strictly before any `Send` interceptor, so the
 * Cloudflare interceptor's per-host clearance UA is applied *after* this and correctly wins — including on
 * a challenge replay, where `Send` re-runs but the request pipeline does not. That ordering holds no matter
 * where this plugin is installed relative to the others.
 *
 * A blank UA is ignored rather than sent, so a future empty settings field can't strip the header entirely.
 */
fun sourceUserAgentPlugin(provider: SourceUserAgentProvider): ClientPlugin<Unit> =
    createClientPlugin("SourceUserAgent") {
        onRequest { request, _ ->
            val userAgent = provider.userAgent().trim()
            if (userAgent.isEmpty()) return@onRequest
            request.headers.remove(HttpHeaders.UserAgent)
            request.headers.append(HttpHeaders.UserAgent, userAgent)
        }
    }