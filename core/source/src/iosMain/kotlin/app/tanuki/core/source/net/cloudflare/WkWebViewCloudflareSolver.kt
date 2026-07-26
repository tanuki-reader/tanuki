package app.tanuki.core.source.net.cloudflare

import app.tanuki.core.domain.common.service.DispatcherProvider
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import platform.CoreGraphics.CGRectZero
import platform.Foundation.NSHTTPCookie
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_after
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_time
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds

/**
 * iOS [CloudflareChallengeSolver] backed by a **headless** [WKWebView] — created offscreen and never
 * added to the view hierarchy, it runs Cloudflare's JS challenge in a full WebKit engine. This is the bit
 * of iOS-specific code CLAUDE.md calls out: the challenge is solved by a real browser, not JSCore, which
 * is precisely why iOS JSCore readers (Suwatte, Paperback) can't pass Cloudflare and Tanuki can.
 *
 * [WKWebView] is main-thread-only, so [solve] hops to `dispatchers.main`; callers may invoke from any
 * dispatcher. It polls the config's cookie store until `cf_clearance` appears, then returns it.
 *
 * > **Scaffold status:** structurally complete (headless load → poll cookie store → return), but the
 * > challenge *timing* and the real solved User-Agent need tuning against a live Cloudflare target
 * > (MangaFire). Today it replays the UA it was handed, or `null` — reading WebKit's own default UA needs
 * > an `evaluateJavaScript("navigator.userAgent")` round-trip, left as a follow-up. See `docs/decisions.md`.
 */
@OptIn(ExperimentalForeignApi::class)
class WkWebViewCloudflareSolver(
    private val dispatchers: DispatcherProvider
) : CloudflareChallengeSolver {
    override suspend fun solve(challenge: CloudflareChallenge): CfClearance? =
        withTimeoutOrNull(SOLVE_TIMEOUT_MS.milliseconds) {
            // WKWebView is main-thread-only: create/load/read must run on Main, not io.
            withContext(dispatchers.main) {
                // The Kotlin/Native NSURL initializer is typed non-null; a malformed string yields a URL
                // WKWebView simply fails to load, which the poll surfaces as a timeout (null clearance).
                val url = NSURL(string = challenge.url)
                val configuration = WKWebViewConfiguration()
                val webView = WKWebView(frame = CGRectZero.readValue(), configuration = configuration)
                challenge.userAgent?.let { webView.customUserAgent = it }
                val solvedUserAgent = challenge.userAgent
                val cookieStore = configuration.websiteDataStore.httpCookieStore

                try {
                    suspendCancellableCoroutine { continuation ->
                        // Re-reads the cookie store on the main queue until cf_clearance appears; the
                        // getAllCookies completion is itself async, so this recurses through dispatch_after
                        // rather than looping. isActive guards against resuming after timeout/cancellation.
                        fun poll() {
                            if (!continuation.isActive) return
                            cookieStore.getAllCookies { cookies ->
                                if (!continuation.isActive) return@getAllCookies
                                val clearance = clearanceFrom(cookies, solvedUserAgent)
                                if (clearance != null) {
                                    continuation.resume(clearance)
                                } else {
                                    dispatch_after(
                                        dispatch_time(DISPATCH_TIME_NOW, POLL_INTERVAL_NS),
                                        dispatch_get_main_queue()
                                    ) { poll() }
                                }
                            }
                        }
                        webView.loadRequest(NSURLRequest(uRL = url))
                        poll()
                    }
                } finally {
                    webView.stopLoading()
                }
            }
        }

    /** Builds a [CfClearance] from the WebKit cookie list once `cf_clearance` is present, else `null`. */
    private fun clearanceFrom(
        cookies: List<*>?,
        userAgent: String?
    ): CfClearance? {
        val pairs =
            cookies
                .orEmpty()
                .filterIsInstance<NSHTTPCookie>()
                .associate { it.name to it.value }
        return if (pairs.containsKey("cf_clearance")) CfClearance(cookies = pairs, userAgent = userAgent) else null
    }

    private companion object {
        const val SOLVE_TIMEOUT_MS = 30_000L

        /** 500 ms in nanoseconds, for dispatch_after between cookie-store polls. */
        const val POLL_INTERVAL_NS = 500_000_000L
    }
}