package app.tanuki.platform.web

import android.annotation.SuppressLint
import android.view.View.OnTouchListener
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import app.tanuki.core.source.net.session.SiteSessionResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * A real, **laid-out and painting** browser engine — Android `WebView`, iOS `WKWebView` — loading [url].
 *
 * The painting is the point, and it is not the same thing as being *seen*. Cloudflare's managed challenge
 * renders a Turnstile widget and will not clear unless that widget actually paints; the offscreen
 * zero-size engines the old Cloudflare solvers used never did, so the challenge's JS ran and then stalled
 * (see [app.tanuki.core.source.net.cloudflare.InteractiveCloudflareSolver]). Being *occluded* by something
 * drawn over or in front of it is different in kind: that happens in the platform compositor, and the page
 * cannot observe it. `SiteSessionHost` leans on exactly that to solve challenges silently — so callers may
 * hide this view, but must never shrink it, detach it, or fade it out.
 *
 * Reports the browser's cookie state to [onSnapshot] on a short poll rather than exposing the engine, so
 * the *decision* about when a session is finished stays in common code and no platform type escapes here.
 * Expect it to be called repeatedly with the same value; callers should treat it as a snapshot, not an event.
 *
 * @param url the page to load.
 * @param clearCookie a cookie to **delete for this host before loading**, or `null` for none. Passing the
 *   cookie the session is waiting for is what forces a genuine re-solve: platform cookie stores are shared
 *   and persistent, so a `cf_clearance` is usually already there, and Cloudflare will happily accept its own
 *   stale cookie in the browser and issue no new one — leaving the session waiting forever for a change that
 *   never comes. Mihon's interceptor does the same thing (`cookieManager.remove(url, COOKIE_NAMES, 0)`)
 *   before it opens its WebView, for the same reason.
 * @param interactive whether the engine may receive touches. `false` while a challenge is being solved out
 *   of sight: the view is full-size and painting but sits *under* the app's own UI, and a tap that reached
 *   it would land on a browser the user cannot see. It does not affect solving — a challenge that needs a
 *   tap is revealed first, and revealing turns this back on.
 * @param onSnapshot receives the browser's current cookies and its real `navigator.userAgent`.
 */
@SuppressLint("SetJavaScriptEnabled") // A Cloudflare challenge *is* JavaScript; without it there is nothing to solve.
@Composable
fun SiteSessionWebView(
    url: String,
    clearCookie: String?,
    interactive: Boolean,
    onSnapshot: (SiteSessionResult) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val latestOnSnapshot by rememberUpdatedState(onSnapshot)

    // Built in `remember` rather than in AndroidView's factory so the poll below has a stable handle
    // without writing snapshot state during composition. Loading is deferred to the effect below, which
    // must expire the stale cookie *first*.
    val webView =
        remember(context, url) {
            val view = WebView(context)
            // Keep navigations inside this view: a challenge redirects to the target page once solved, and
            // the default client would hand that off to an external browser, taking the cookie with it.
            view.webViewClient = WebViewClient()
            view.settings.javaScriptEnabled = true
            view.settings.domStorageEnabled = true
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                // Turnstile runs in an iframe from challenges.cloudflare.com, so its cookies are
                // third-party relative to the source's host.
                setAcceptThirdPartyCookies(view, true)
            }
            view
        }

    LaunchedEffect(webView, url, clearCookie) {
        if (clearCookie != null) CookieManager.getInstance().expire(url, clearCookie)
        webView.loadUrl(url)
    }

    AndroidView(
        factory = { webView },
        // Touch handling is the only thing that changes over this view's life, and it is set here rather
        // than in the factory so flipping it never rebuilds the engine — a new WebView would abandon a
        // challenge that is seconds from clearing and start a fresh one.
        update = { view -> view.setInteractive(interactive) },
        modifier = modifier,
    )

    LaunchedEffect(webView, url) {
        val cookieManager = CookieManager.getInstance()
        // Constant for a given engine build, so read once it's available rather than every 400ms. Empty
        // until the first page has loaded enough to run script.
        var clientHints: Map<String, String> = emptyMap()
        while (true) {
            if (clientHints.isEmpty()) clientHints = webView.readClientHints()
            val challenge = webView.probeChallenge()
            latestOnSnapshot(
                SiteSessionResult(
                    cookies = parseCookieString(cookieManager.getCookie(url)),
                    // The engine's own UA — the one the challenge is being solved under, which a
                    // cf_clearance is bound to. Never overridden; see DEFAULT_SOURCE_USER_AGENT.
                    userAgent = webView.settings.userAgentString,
                    headers = clientHints,
                    onChallengePage = challenge.onChallengePage,
                    challengeType = challenge.type,
                ),
            )
            delay(COOKIE_POLL_INTERVAL_MS)
        }
    }

    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }
}

/** Asks the page whether it is a Cloudflare interstitial, and what kind. See [parseChallengeProbe]. */
private suspend fun WebView.probeChallenge(): ChallengeProbe {
    val result =
        suspendCancellableCoroutine { continuation ->
            evaluateJavascript(CHALLENGE_PROBE_JS) { value -> continuation.resume(value) }
        }
    return parseChallengeProbe(result)
}

/**
 * Lets the engine see touches, or drops them before it does.
 *
 * Returning `true` from the listener consumes the event: while a challenge is being solved out of sight
 * the view is full-size and painting *under* the app's own UI, and Compose will happily route a tap that
 * nothing above consumed down to it. Clearing the listener (rather than passing one that returns `false`)
 * restores the view's own handling, which is what a revealed challenge needs.
 */
@SuppressLint("ClickableViewAccessibility") // A browser engine has no click semantics to announce.
private fun WebView.setInteractive(interactive: Boolean) {
    setOnTouchListener(if (interactive) null else OnTouchListener { _, _ -> true })
}

/**
 * Expires [name] for [url]'s host, so the browser has to earn a new one.
 *
 * Written for both the bare host and the dot-prefixed parent, because Cloudflare sets `cf_clearance` on
 * `.example.com` while the request URL is `example.com` — overwriting only one leaves the other live.
 */
private fun CookieManager.expire(
    url: String,
    name: String,
) {
    val host = url.substringAfter("://").substringBefore('/').substringBefore(':')
    val expiry = "Max-Age=0; Path=/; Expires=Thu, 01 Jan 1970 00:00:00 GMT"
    setCookie(url, "$name=; $expiry; Domain=$host")
    setCookie(url, "$name=; $expiry; Domain=.$host")
    setCookie(url, "$name=; $expiry")
    flush()
}

/**
 * Asks the page for the client hints this engine sends, so a clearance can be replayed with the same
 * browser identity that earned it. Empty when script can't run yet or anything goes wrong — the hints are
 * an improvement to the replay, never a reason to fail a solve.
 */
@OptIn(ExperimentalEncodingApi::class)
private suspend fun WebView.readClientHints(): Map<String, String> {
    val encoded =
        suspendCancellableCoroutine { continuation ->
            evaluateJavascript(CLIENT_HINTS_JS) { result -> continuation.resume(result) }
        }
    // evaluateJavascript hands back a JSON value: a base64 payload arrives as "…", null on failure.
    val payload = encoded?.trim()?.removeSurrounding("\"").orEmpty()
    if (payload.isEmpty()) return emptyMap()
    return runCatching { parseHeaderLines(Base64.decode(payload).decodeToString()) }.getOrDefault(emptyMap())
}