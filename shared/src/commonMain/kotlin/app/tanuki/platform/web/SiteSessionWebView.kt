package app.tanuki.platform.web

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.tanuki.core.source.net.session.SiteSessionResult

/**
 * A real, **on-screen** browser engine — Android `WebView`, iOS `WKWebView` — loading [url].
 *
 * The visibility is the point. Cloudflare's managed challenge renders a Turnstile widget and will not
 * clear unless that widget actually paints, and it can escalate to a checkbox only a person can tap; the
 * offscreen zero-size engines the Cloudflare solvers used could do neither (see
 * [app.tanuki.core.source.net.cloudflare.InteractiveCloudflareSolver]). The same surface serves a source
 * login, where the user has to type into the page.
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
 * @param onSnapshot receives the browser's current cookies and its real `navigator.userAgent`.
 */
@Composable
expect fun SiteSessionWebView(
    url: String,
    clearCookie: String?,
    onSnapshot: (SiteSessionResult) -> Unit,
    modifier: Modifier = Modifier,
)

/**
 * How often the actuals re-read the cookie store. Short enough that a challenge clearing on its own
 * dismisses before the user reaches for the close button, cheap enough to run for a whole login.
 */
internal const val COOKIE_POLL_INTERVAL_MS = 400L

/**
 * Reads the Chromium user-agent client hints the engine sends on every request, as `Name: value` lines,
 * **base64-encoded**. Cloudflare names these in the challenge's `critical-ch` header and revalidates them,
 * so a `cf_clearance` earned by Chromium is only replayable alongside them.
 *
 * Base64 so the result survives `evaluateJavascript`, which hands back a JSON-encoded value: the hint
 * values are full of quotes (`"Chromium";v="150"`) and unescaping that is a needless place to be wrong.
 * Returns `""` where `navigator.userAgentData` doesn't exist — WebKit, which sends no hints at all.
 */
internal const val CLIENT_HINTS_JS: String = """
(function () {
  var data = navigator.userAgentData;
  if (!data) return "";
  var brands = data.brands
    .map(function (b) { return '"' + b.brand + '";v="' + b.version + '"'; })
    .join(', ');
  return btoa([
    'Sec-CH-UA: ' + brands,
    'Sec-CH-UA-Mobile: ' + (data.mobile ? '?1' : '?0'),
    'Sec-CH-UA-Platform: "' + data.platform + '"'
  ].join('\n'));
})()
"""

/**
 * Whether the browser is sitting on a Cloudflare interstitial, as `"1"` or `"0"`.
 *
 * Keys off `window._cf_chl_opt`, the options object every Cloudflare challenge page defines (it carries
 * `cType: 'managed'` and friends). Precise and locale-independent, unlike matching the "Just a moment..."
 * title — and unlike the `cf_chl_*` cookies, which linger in the jar long after the flow has finished and
 * so cannot tell a half-done challenge from a done one.
 */
internal const val ON_CHALLENGE_PAGE_JS: String =
    "(function () { return (typeof window._cf_chl_opt !== 'undefined') ? '1' : '0'; })()"

/** Parses the decoded [CLIENT_HINTS_JS] payload — `Name: value` lines — into headers. */
internal fun parseHeaderLines(decoded: String): Map<String, String> =
    decoded
        .lineSequence()
        .mapNotNull { line ->
            val separator = line.indexOf(':')
            if (separator <= 0) null else line.take(separator).trim() to line.substring(separator + 1).trim()
        }.toMap()

/** Parses a `name=value; name=value` cookie string into pairs, ignoring malformed entries. */
internal fun parseCookieString(raw: String?): Map<String, String> =
    raw
        .orEmpty()
        .split(';')
        .mapNotNull { part ->
            val separator = part.indexOf('=')
            if (separator <= 0) null else part.take(separator).trim() to part.substring(separator + 1).trim()
        }.toMap()