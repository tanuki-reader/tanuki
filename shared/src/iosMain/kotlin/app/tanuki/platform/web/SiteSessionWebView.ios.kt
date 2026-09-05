package app.tanuki.platform.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import app.tanuki.core.source.net.session.SiteSessionResult
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.CoreGraphics.CGRectZero
import platform.Foundation.NSHTTPCookie
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.WebKit.WKHTTPCookieStore
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import kotlin.coroutines.resume

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun SiteSessionWebView(
    url: String,
    clearCookie: String?,
    interactive: Boolean,
    onSnapshot: (SiteSessionResult) -> Unit,
    modifier: Modifier,
) {
    val latestOnSnapshot by rememberUpdatedState(onSnapshot)

    // The default data store is persistent and shared, which is what we want: a cf_clearance solved here
    // is the same one the Ktor client replays, and a login survives the sheet being dismissed.
    val configuration = remember { WKWebViewConfiguration() }
    val webView =
        remember(configuration) {
            // UIKitView gives the view its real frame on layout; CGRectZero is only the initial value.
            WKWebView(frame = CGRectZero.readValue(), configuration = configuration)
        }

    // Load only after the stale cookie is gone, so the browser must genuinely re-solve rather than being
    // waved through on a clearance we already know the HTTP client can't use.
    LaunchedEffect(webView, url, clearCookie) {
        if (clearCookie != null) {
            configuration.websiteDataStore.httpCookieStore.deleteNamed(clearCookie, hostOf(url))
        }
        webView.loadRequest(NSURLRequest(uRL = NSURL(string = url)))
    }

    // Touch handling is the only thing that changes over this view's life, and it is applied in `update`
    // rather than the factory so flipping it never rebuilds the engine — a new WKWebView would abandon a
    // challenge that is seconds from clearing and start a fresh one.
    UIKitView(factory = { webView }, update = { view -> view.userInteractionEnabled = interactive }, modifier = modifier)

    LaunchedEffect(webView, url) {
        val cookieStore = configuration.websiteDataStore.httpCookieStore
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
            latestOnSnapshot(
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

    DisposableEffect(webView) {
        onDispose { webView.stopLoading() }
    }
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