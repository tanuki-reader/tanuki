package app.tanuki.core.source.net

import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.source.net.cloudflare.CloudflareChallengeSolver
import app.tanuki.core.source.net.cloudflare.CloudflareCookieStore
import app.tanuki.core.source.net.cloudflare.cloudflarePlugin
import app.tanuki.core.source.net.cookie.SourceCookieJar
import app.tanuki.core.source.net.cookie.sourceCookiePlugin
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig

/**
 * The shared HTTP client — one per process, every source request (and the cover/page image loader) flows
 * through it. Ktor on every platform; only the engine differs (OkHttp on Android, Darwin on iOS), which
 * is all the `actual` supplies. The **Cloudflare interceptor and the cookie jar are installed here, in
 * common code** ([installSourcePlugins]), so the bypass, the `cf_clearance` replay and session cookies
 * behave identically on both platforms and no source can sidestep them.
 *
 * @param solver platform browser-engine challenge solver, injected per platform.
 * @param cookieStore per-host clearance cache shared across requests.
 * @param cookieJar per-host jar for the sites' own cookies.
 * @param userAgent supplies the one `User-Agent` all source traffic goes out with.
 * @param logger records challenge/solve outcomes; the only view into a bypass that silently did nothing.
 */
expect fun platformSourceHttpClient(
    solver: CloudflareChallengeSolver,
    cookieStore: CloudflareCookieStore,
    cookieJar: SourceCookieJar,
    userAgent: SourceUserAgentProvider,
    logger: Logger,
): HttpClient

/**
 * Common client configuration, applied by every platform `actual` so the two clients differ only by
 * engine. Turns off `expectSuccess` — the Cloudflare interceptor inspects `403`/`503` responses itself to
 * detect a challenge, so Ktor must not throw on them first — and installs the three request plugins.
 *
 * **Install order is load-bearing**: Ktor runs `Send` interceptors in installation order, each wrapping
 * the next, so the Cloudflare plugin goes on first and the cookie jar ends up innermost — right against
 * the engine, seeing every physical attempt including a challenge replay. See [sourceCookiePlugin] for
 * why that is the position it has to have. The user-agent plugin is exempt from that reasoning: it hooks
 * the request pipeline rather than `Send`, so it always runs first regardless (see [sourceUserAgentPlugin]).
 */
fun HttpClientConfig<*>.installSourcePlugins(
    solver: CloudflareChallengeSolver,
    cookieStore: CloudflareCookieStore,
    cookieJar: SourceCookieJar,
    userAgent: SourceUserAgentProvider,
    logger: Logger,
) {
    expectSuccess = false
    install(sourceUserAgentPlugin(userAgent))
    install(cloudflarePlugin(solver, cookieStore, logger))
    install(sourceCookiePlugin(cookieJar))
}