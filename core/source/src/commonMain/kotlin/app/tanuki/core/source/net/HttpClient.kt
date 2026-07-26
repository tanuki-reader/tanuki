package app.tanuki.core.source.net

import app.tanuki.core.source.net.cloudflare.CloudflareChallengeSolver
import app.tanuki.core.source.net.cloudflare.CloudflareCookieStore
import app.tanuki.core.source.net.cloudflare.cloudflarePlugin
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig

/**
 * The shared HTTP client — one per process, every source request (and, later, the cover/page image
 * loader) flows through it. Ktor on every platform; only the engine differs (OkHttp on Android, Darwin
 * on iOS), which is all the `actual` supplies. The **Cloudflare interceptor is installed here, in common
 * code** ([installCloudflare]), so the bypass and `cf_clearance` replay apply identically on both
 * platforms and no source can sidestep them.
 *
 * @param solver platform browser-engine challenge solver, injected per platform.
 * @param cookieStore per-host clearance cache shared across requests.
 */
expect fun platformSourceHttpClient(
    solver: CloudflareChallengeSolver,
    cookieStore: CloudflareCookieStore,
): HttpClient

/**
 * Common client configuration, applied by every platform `actual` so the two clients differ only by
 * engine. Installs the [cloudflarePlugin] and turns off `expectSuccess` — the interceptor inspects
 * `403`/`503` responses itself to detect a challenge, so Ktor must not throw on them first.
 */
fun HttpClientConfig<*>.installCloudflare(
    solver: CloudflareChallengeSolver,
    cookieStore: CloudflareCookieStore,
) {
    expectSuccess = false
    install(cloudflarePlugin(solver, cookieStore))
}