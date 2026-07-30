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
 * Note what is deliberately **not** here: the failed request's `User-Agent`. Handing it to the solver
 * invites the solver to impose it on the browser engine, and Turnstile cross-checks the UA against the JS
 * environment — so a Chromium/WebKit claiming to be the app's UA never clears the challenge. Solvers use
 * their engine's own UA and report it back in [CfClearance]; see
 * [app.tanuki.core.source.net.DEFAULT_SOURCE_USER_AGENT] for the UA ordinary (unchallenged) traffic uses.
 *
 * @property url the exact URL that was challenged — the browser engine loads this.
 */
data class CloudflareChallenge(
    val url: String,
)

/**
 * The result of a solved challenge: the cookies to replay, and **the browser identity they are bound to**.
 *
 * Cloudflare ties a `cf_clearance` to the client IP and to how the browser identifies itself. Replaying
 * the cookie as anything else is rejected, so the identity travels with the cookie rather than being
 * reconstructed by the caller — [userAgent] and [headers] are part of the clearance, not decoration.
 *
 * @property cookies clearance cookies by name (at least `cf_clearance`); replayed as a `Cookie` header.
 * @property userAgent the UA the challenge was solved under, which every replay must send; `null` when the
 *   engine couldn't report it (then the caller keeps the request's own UA).
 * @property headers other request headers that are part of that identity, replayed verbatim. In practice
 *   the Chromium user-agent client hints (`Sec-CH-UA`, `Sec-CH-UA-Mobile`, `Sec-CH-UA-Platform`): Cloudflare
 *   asks for them by name on its challenge (`accept-ch`, and `critical-ch` — meaning it revalidates them),
 *   and a request claiming a Chrome UA while sending none is a contradiction it re-challenges. Empty on
 *   WebKit, which has no client hints to send — which is exactly why iOS worked while Android did not.
 */
data class CfClearance(
    val cookies: Map<String, String>,
    val userAgent: String?,
    val headers: Map<String, String> = emptyMap(),
)