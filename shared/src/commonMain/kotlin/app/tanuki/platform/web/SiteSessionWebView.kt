package app.tanuki.platform.web

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.tanuki.core.source.net.session.ChallengeType
import app.tanuki.core.source.net.session.SiteSessionResult

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
@Composable
expect fun SiteSessionWebView(
    url: String,
    clearCookie: String?,
    interactive: Boolean,
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
 * Whether the browser is sitting on a Cloudflare interstitial and, if so, what kind — as `"0|"` or
 * `"1|<cType>"`.
 *
 * Keys off `window._cf_chl_opt`, the options object every Cloudflare challenge page defines. Precise and
 * locale-independent, unlike matching the "Just a moment..." title — and unlike the `cf_chl_*` cookies,
 * which linger in the jar long after the flow has finished and so cannot tell a half-done challenge from a
 * done one.
 *
 * The `cType` half rides along on the poll that was already running, rather than a second evaluation:
 * it is read from the same object, and the two answers must describe the same instant or the caller could
 * hold a type belonging to a page it has already left. See [ChallengeType].
 *
 * One flat string rather than JSON because both engines hand a JS value back through a stringly-typed
 * callback, and a delimiter is one parse that cannot be wrong — `cType` is a fixed vocabulary of
 * lowercase words with no `|` in it.
 */
internal const val CHALLENGE_PROBE_JS: String =
    """
(function () {
  var options = window._cf_chl_opt;
  if (typeof options === 'undefined') return '0|';
  return '1|' + (options.cType || '');
})()
"""

/**
 * What [CHALLENGE_PROBE_JS] found. See [SiteSessionResult.onChallengePage] / [SiteSessionResult.challengeType].
 */
internal data class ChallengeProbe(
    val onChallengePage: Boolean,
    val type: ChallengeType,
)

/**
 * Parses [CHALLENGE_PROBE_JS]'s answer, as either engine hands it back: Android's `evaluateJavascript`
 * returns a JSON value (so a quoted string, or the literal `null`), WebKit's returns the string itself.
 *
 * An unreadable answer means **still challenged**, which is the safe direction and matches what the probe
 * did before it reported a type: a page mid-navigation returns nothing, and treating that as "challenge
 * over" would resume the caller on a half-solved challenge, while treating it as "still going" costs one
 * more poll.
 */
internal fun parseChallengeProbe(raw: String?): ChallengeProbe {
    val value = raw?.trim()?.removeSurrounding("\"").orEmpty()
    if (value.isEmpty() || value == "null") return ChallengeProbe(onChallengePage = true, type = ChallengeType.Unknown)
    val onChallengePage = value.substringBefore('|') != "0"
    return ChallengeProbe(
        onChallengePage = onChallengePage,
        // A page that is not a challenge has no type to report, whatever came back after the delimiter.
        type = if (onChallengePage) ChallengeType.fromCType(value.substringAfter('|', "")) else ChallengeType.Unknown,
    )
}

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