package app.tanuki.core.source.net.cloudflare

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import app.tanuki.core.domain.common.service.DispatcherProvider
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds

/**
 * Android [CloudflareChallengeSolver] backed by a real (offscreen, never-attached) [WebView] — the
 * Chromium engine runs Cloudflare's JS challenge exactly as a browser would, which is what lets Tanuki
 * pass challenges that a headless HTTP client cannot.
 *
 * A [WebView] must be created and driven on the **main thread**, so [solve] hops to `dispatchers.main`;
 * the caller can invoke it from any dispatcher. The view is destroyed in all exit paths (success,
 * timeout, cancellation) so a solve never leaks a WebView or its JS timers.
 *
 * > **Scaffold status:** the mechanism (load → poll for `cf_clearance` → return with the WebView's UA) is
 * > complete, but the *timing* — how long a managed challenge / Turnstile takes to settle, and whether a
 * > page needs interaction — can only be tuned against a live Cloudflare target (MangaFire). The polling
 * > timeout and interval below are first estimates. See `docs/decisions.md`.
 */
class WebViewCloudflareSolver(
    private val context: Context,
    private val dispatchers: DispatcherProvider
) : CloudflareChallengeSolver {
    @SuppressLint("SetJavaScriptEnabled")
    override suspend fun solve(challenge: CloudflareChallenge): CfClearance? =
        withTimeoutOrNull(SOLVE_TIMEOUT_MS.milliseconds) {
            // WebView is main-thread-only: create/load/read/destroy must run on Main, not io.
            withContext(dispatchers.main) {
                val webView = WebView(context.applicationContext)
                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(webView, true)

                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    // A Cloudflare challenge inspects the UA; the challenge's cf_clearance is bound to it,
                    // so the UA the view reports here is exactly the one the interceptor must replay.
                    challenge.userAgent?.let { userAgentString = it }
                }
                val solvedUserAgent = webView.settings.userAgentString

                try {
                    suspendCancellableCoroutine { continuation ->
                        val handler = Handler(Looper.getMainLooper())
                        val poll =
                            object : Runnable {
                                override fun run() {
                                    if (!continuation.isActive) return
                                    val clearance = cookieManager.clearanceFor(challenge.url, solvedUserAgent)
                                    if (clearance != null) {
                                        continuation.resume(clearance)
                                    } else {
                                        handler.postDelayed(this, POLL_INTERVAL_MS)
                                    }
                                }
                            }
                        continuation.invokeOnCancellation { handler.removeCallbacks(poll) }
                        webView.loadUrl(challenge.url)
                        handler.postDelayed(poll, POLL_INTERVAL_MS)
                    }
                } finally {
                    webView.stopLoading()
                    webView.destroy()
                }
            }
        }

    /** Reads the current cookies for [url] and returns a [CfClearance] once `cf_clearance` is present. */
    private fun CookieManager.clearanceFor(
        url: String,
        userAgent: String
    ): CfClearance? {
        val raw = getCookie(url) ?: return null
        val cookies =
            raw
                .split(';')
                .mapNotNull { part ->
                    val eq = part.indexOf('=')
                    if (eq <= 0) null else part.take(eq).trim() to part.substring(eq + 1).trim()
                }.toMap()
        return cookies["cf_clearance"]?.let { CfClearance(cookies = cookies, userAgent = userAgent) }
    }

    private companion object {
        const val SOLVE_TIMEOUT_MS = 30_000L
        const val POLL_INTERVAL_MS = 500L
    }
}