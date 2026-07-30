package app.tanuki.platform.web

import android.annotation.SuppressLint
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

@SuppressLint("SetJavaScriptEnabled") // A Cloudflare challenge *is* JavaScript; without it there is nothing to solve.
@Composable
actual fun SiteSessionWebView(
    url: String,
    clearCookie: String?,
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

    AndroidView(factory = { webView }, modifier = modifier)

    LaunchedEffect(webView, url) {
        val cookieManager = CookieManager.getInstance()
        // Constant for a given engine build, so read once it's available rather than every 400ms. Empty
        // until the first page has loaded enough to run script.
        var clientHints: Map<String, String> = emptyMap()
        while (true) {
            if (clientHints.isEmpty()) clientHints = webView.readClientHints()
            latestOnSnapshot(
                SiteSessionResult(
                    cookies = parseCookieString(cookieManager.getCookie(url)),
                    // The engine's own UA — the one the challenge is being solved under, which a
                    // cf_clearance is bound to. Never overridden; see DEFAULT_SOURCE_USER_AGENT.
                    userAgent = webView.settings.userAgentString,
                    headers = clientHints,
                    onChallengePage = webView.isOnChallengePage(),
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

/** Asks the page whether it is a Cloudflare interstitial; assumes it is if the answer can't be read. */
private suspend fun WebView.isOnChallengePage(): Boolean {
    val result =
        suspendCancellableCoroutine { continuation ->
            evaluateJavascript(ON_CHALLENGE_PAGE_JS) { value -> continuation.resume(value) }
        }
    // A page mid-navigation can return null; treating that as "still on the challenge" only delays
    // finishing by one poll, whereas the opposite would resume the caller on a half-solved challenge.
    return result?.trim()?.removeSurrounding("\"") != "0"
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