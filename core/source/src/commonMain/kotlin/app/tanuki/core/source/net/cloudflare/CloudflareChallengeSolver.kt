package app.tanuki.core.source.net.cloudflare

/**
 * Solves a Cloudflare "I'm Under Attack" / managed-challenge by loading it in a **real browser engine**
 * — the one thing a headless HTTP client can't do, and the reason iOS JSCore readers (Suwatte, Paperback)
 * fail where Tanuki won't (see CLAUDE.md → Cloudflare Bypass).
 *
 * This is the `expect`/`actual` seam: the interceptor logic ([cloudflarePlugin]) is common, the browser
 * engine is platform-specific — Android `WebView`, iOS headless `WKWebView` — and injected via DI
 * ([app.tanuki.core.source.di.PlatformSourceBindings]). It is deliberately **independent of the WASM
 * source loader**: any request on the shared client can be challenged, so the solver knows nothing about
 * sources.
 *
 * Implementations must be safe to call from any dispatcher (they hop to the platform main thread
 * internally) and must never throw for an unsolved challenge — return `null` so the caller surfaces the
 * original response rather than a crash.
 */
interface CloudflareChallengeSolver {
    /**
     * Loads [challenge] in the platform browser engine, waits for Cloudflare to issue a `cf_clearance`
     * cookie, and returns it bound to the user agent it was solved with.
     *
     * @return the clearance to replay on later requests, or `null` if it could not be solved within
     *   the timeout (offline, a non-interactive block, or a challenge kind we don't handle yet).
     */
    suspend fun solve(challenge: CloudflareChallenge): CfClearance?
}

/**
 * A request that came back as a Cloudflare challenge and needs solving.
 *
 * @property url the exact URL that was challenged — the browser engine loads this.
 * @property userAgent the user agent the failed request used, or `null`. The solver may reuse it or fall
 *   back to the engine's own default; whichever actually solves the challenge is returned in [CfClearance].
 */
data class CloudflareChallenge(
    val url: String,
    val userAgent: String?
)

/**
 * The result of a solved challenge: the cookies to replay and the user agent they are bound to.
 *
 * Cloudflare ties a `cf_clearance` cookie to **both** the client IP and the exact `User-Agent` string, so
 * replaying the cookie without the same UA fails. [userAgent] is therefore part of the clearance, not an
 * afterthought — the interceptor sets it on every replayed request.
 *
 * @property cookies clearance cookies by name (at least `cf_clearance`); replayed as a `Cookie` header.
 * @property userAgent the UA the challenge was solved under, which every replay must send; `null` when the
 *   engine couldn't report it (then the caller keeps the request's own UA — a live-tuning gap, see actuals).
 */
data class CfClearance(
    val cookies: Map<String, String>,
    val userAgent: String?
)