package app.tanuki.platform.web

import app.tanuki.core.source.net.session.SiteSessionResult
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.CoreGraphics.CGRectZero
import platform.Foundation.NSHTTPCookie
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.WebKit.WKHTTPCookieStore
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import kotlin.coroutines.resume

/**
 * The browser a site session (Cloudflare challenge, login) runs in: a `WKWebView` loading [url], and a poll
 * that reports its cookies, user agent and challenge state to [onSnapshot]. SwiftUI's `SiteSessionLayer`
 * mounts [webView] and forwards the reports to `SiteSessionViewModel`, which decides when it's done.
 *
 * The view must be **laid out and painting** for a challenge to solve, which is not the same as being seen.
 * Cloudflare's managed challenge renders a Turnstile widget and won't clear unless it actually paints; a
 * zero-size, detached engine never does. Being *occluded* by the app drawn over it happens in the compositor
 * and the page can't observe it, so the layer may hide this view but must never shrink, detach or fade it.
 *
 * Starts loading on construction; one per session. Call [close] when the session ends.
 *
 * @param clearCookie a cookie to **delete for this host before loading**, or `null`. Platform cookie stores
 *   are shared and persistent, so a stale `cf_clearance` is usually there, and Cloudflare accepts its own
 *   stale cookie in the browser without issuing a new one, leaving the session waiting forever. Mihon's
 *   interceptor does the same before it opens its WebView.
 */
@OptIn(ExperimentalForeignApi::class)
class SiteSessionBrowser(
    url: String,
    clearCookie: String?,
    onSnapshot: (SiteSessionResult) -> Unit,
) {
    // The default data store is persistent and shared, which is what we want: a cf_clearance solved here
    // is the same one the Ktor client replays, and a login survives the layer being dismissed.
    private val configuration = WKWebViewConfiguration()

    /** The engine. SwiftUI gives it its real frame on layout; CGRectZero is only the initial value. */
    val webView: WKWebView = WKWebView(frame = CGRectZero.readValue(), configuration = configuration)

    private val scope = MainScope()

    init {
        scope.launch {
            val cookieStore = configuration.websiteDataStore.httpCookieStore
            // Load only after the stale cookie is gone, so the browser must genuinely re-solve rather than
            // being waved through on a clearance we already know the HTTP client can't use.
            if (clearCookie != null) cookieStore.deleteNamed(clearCookie, hostOf(url))
            webView.loadRequest(NSURLRequest(uRL = NSURL(string = url)))

            while (true) {
                val cookies =
                    suspendCancellableCoroutine { continuation ->
                        cookieStore.getAllCookies { all -> continuation.resume(all) }
                    }
                val userAgent =
                    suspendCancellableCoroutine { continuation ->
                        // WebKit's real UA, which the clearance is bound to. Reading it from the page rather
                        // than assuming a string is what keeps a replay acceptable to Cloudflare.
                        webView.evaluateJavaScript("navigator.userAgent") { result, _ ->
                            continuation.resume(result as? String)
                        }
                    }
                val challenge =
                    suspendCancellableCoroutine { continuation ->
                        webView.evaluateJavaScript(CHALLENGE_PROBE_JS) { result, _ ->
                            // null mid-navigation: parsed as still challenged, costing at most one more poll.
                            continuation.resume(parseChallengeProbe(result as? String))
                        }
                    }
                onSnapshot(
                    SiteSessionResult(
                        cookies =
                            cookies
                                .orEmpty()
                                .filterIsInstance<NSHTTPCookie>()
                                .associate { cookie -> cookie.name to cookie.value },
                        userAgent = userAgent,
                        onChallengePage = challenge.onChallengePage,
                        challengeType = challenge.type,
                    ),
                )
                delay(COOKIE_POLL_INTERVAL_MS)
            }
        }
    }

    /** Stops the poll and the page. */
    fun close() {
        scope.cancel()
        webView.stopLoading()
    }
}

/** Whether this process has already touched WebKit. Main-thread only, like `WKWebView` itself. */
private var warmed = false

/**
 * Starts WebKit once, a little after launch, so the first Cloudflare challenge doesn't pay for it mid-scroll.
 * Building one `WKWebView` links and initializes the framework and its data store; the instance is thrown
 * away (holding it would trade memory a page decode wants for a challenge that may never come). Called
 * from the SwiftUI root's `.task`; idempotent.
 */
@OptIn(ExperimentalForeignApi::class)
suspend fun warmUpWebEngine() =
    withContext(Dispatchers.Main) {
        if (warmed) return@withContext
        delay(WEB_ENGINE_WARM_UP_DELAY_MS)
        warmed = true
        WKWebView(frame = CGRectZero.readValue(), configuration = WKWebViewConfiguration())
        Unit
    }

/** The host part of [url], for matching cookie domains. */
private fun hostOf(url: String): String = url.substringAfter("://").substringBefore('/').substringBefore(':')

/**
 * Deletes every cookie called [name] that applies to [host], so the browser has to earn a new one. Matches
 * the dot-prefixed parent too — Cloudflare sets `cf_clearance` on `.example.com` while the request is to
 * `example.com`, and leaving either copy live defeats the point.
 */
@OptIn(ExperimentalForeignApi::class)
private suspend fun WKHTTPCookieStore.deleteNamed(
    name: String,
    host: String,
) {
    val cookies =
        suspendCancellableCoroutine { continuation ->
            getAllCookies { all -> continuation.resume(all) }
        }
    cookies
        .orEmpty()
        .filterIsInstance<NSHTTPCookie>()
        .filter { it.name == name && host.endsWith(it.domain.removePrefix(".")) }
        .forEach { cookie ->
            suspendCancellableCoroutine { continuation ->
                deleteCookie(cookie) { continuation.resume(Unit) }
            }
        }
}