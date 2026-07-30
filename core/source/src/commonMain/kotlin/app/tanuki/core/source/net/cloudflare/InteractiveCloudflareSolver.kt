package app.tanuki.core.source.net.cloudflare

import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.domain.common.service.info
import app.tanuki.core.domain.common.service.warn
import app.tanuki.core.source.net.session.SiteSessionCompletion
import app.tanuki.core.source.net.session.SiteSessionCoordinator
import app.tanuki.core.source.net.session.SiteSessionReason
import app.tanuki.core.source.net.session.SiteSessionRequest
import io.ktor.http.Url

/**
 * A [CloudflareChallengeSolver] that shows the challenge to the user, via [SiteSessionCoordinator].
 *
 * ### Why the headless solvers weren't enough
 * MangaFire serves a Cloudflare **managed challenge** (`cType: 'managed'`), which renders a real Turnstile
 * widget. Both `WebViewCloudflareSolver` and `WkWebViewCloudflareSolver` create their browser offscreen at
 * zero size and never attach it to a window, so the widget never paints — the challenge's JS runs (a device
 * log shows its WebRTC fingerprinting probes) and then stalls until the 30-second timeout. Verified on an
 * Android emulator *and* a physical iPhone, which rules out the emulator's broken GPU as the cause.
 *
 * A managed challenge can also escalate to a checkbox the person has to tap, and nothing headless can do
 * that on their behalf. So the reliable answer is the one Mihon reaches for: put the challenge on screen.
 *
 * The session ends by itself the moment `cf_clearance` appears ([SiteSessionCompletion.WhenCookieSet]), so
 * a challenge that clears without interaction flashes by rather than demanding a tap.
 *
 * Common code: this knows nothing about `WebView` or `WKWebView` — the coordinator's UI host owns the
 * engine, exactly as the `expect`/`actual` solvers did.
 */
class InteractiveCloudflareSolver(
    private val coordinator: SiteSessionCoordinator,
    private val logger: Logger,
) : CloudflareChallengeSolver {

    override suspend fun solve(challenge: CloudflareChallenge): CfClearance? {
        // Defensive: the interceptor always hands over an absolute URL (`request.url.buildString()`), but
        // nothing in the type says so, and Ktor's parser is lenient — it resolves a relative string against
        // its `localhost` default rather than failing. Showing the user a browser pointed at the wrong
        // place, or at nothing, would park the request until they dismissed it. Check the scheme up front
        // rather than trusting the parse.
        if (!challenge.url.startsWith("http://") && !challenge.url.startsWith("https://")) {
            logger.warn(TAG, "Not asking for a session: '${challenge.url}' is not an absolute URL")
            return null
        }
        val host = runCatching { Url(challenge.url).host }.getOrNull().orEmpty()
        if (host.isBlank()) {
            logger.warn(TAG, "Not asking for a session: no host in '${challenge.url}'")
            return null
        }

        // Logged unconditionally. When a source 403s, the first question is whether the challenge was
        // detected at all, and the second is whether anything was on screen to answer it — from the
        // outside both look identical, a plain HTTP 403 out of the guest.
        logger.info(TAG, "Challenge on '$host'; showing a session (ui attached=${coordinator.isAttached})")

        // Solve on the host **root**, not the URL that was challenged. A `cf_clearance` is a domain-scoped
        // cookie, so the root earns the same one — and the challenged URL is usually an API endpoint, which
        // leaves the browser sitting on raw JSON once the challenge clears. That is confusing to look at,
        // and it is a document the `_cf_chl_opt` probe cannot reliably run against. The root is ordinary
        // HTML, which is both a sensible thing to show someone and a reliable place to probe.
        val scheme = if (challenge.url.startsWith("http://")) "http" else "https"
        val result = coordinator.request(
            SiteSessionRequest(
                url = "$scheme://$host/",
                host = host,
                completion = SiteSessionCompletion.WhenCookieSet(CLEARANCE_COOKIE),
                reason = SiteSessionReason.CLOUDFLARE_CHALLENGE,
            ),
        )
        if (result == null) {
            logger.warn(TAG, "Session for '$host' produced nothing — dismissed, or no UI was attached")
            return null
        }

        // The user may have closed the session early, or confirmed one that never actually cleared.
        // Without the cookie there is no clearance to store — returning null makes the interceptor
        // surface the original challenge instead of replaying a useless cookie set.
        if (!result.cookies.containsKey(CLEARANCE_COOKIE)) {
            logger.warn(TAG, "Session for '$host' ended with no $CLEARANCE_COOKIE (cookies: ${result.cookies.keys})")
            return null
        }
        logger.info(TAG, "Solved '$host' (ua=${result.userAgent}, hints=${result.headers.keys})")
        return CfClearance(
            cookies = result.cookies,
            userAgent = result.userAgent,
            headers = result.headers,
        )
    }

    private companion object {
        const val CLEARANCE_COOKIE = "cf_clearance"

        const val TAG = "Cloudflare"
    }
}