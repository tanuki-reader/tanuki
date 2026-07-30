package app.tanuki.core.source.net.cloudflare

import io.ktor.client.statement.HttpResponse

/**
 * Whether [this] response is a Cloudflare challenge the [cloudflarePlugin] should try to solve, decided
 * from **headers only** — the body is left unread so detection never consumes a stream the caller still
 * needs.
 *
 * The question is narrower than "did Cloudflare block this": it is "is there a challenge a browser engine
 * can *answer*". A WAF block (error 1020), a rate limit, or the origin's own `403` are all final — opening
 * a WebView for them buys a 30-second timeout and the same failure. So detection keys on markers Cloudflare
 * sets **only when it is serving a challenge**, both verified against a live MangaFire interstitial:
 *
 *  - `cf-mitigated: challenge` — the explicit, modern marker on an interactive/managed challenge.
 *  - `server-timing: chlray;desc="…"` — the *challenge* ray id, emitted only on a challenge page (ordinary
 *    Cloudflare responses carry `cf-ray` but no `chlray`).
 *  - `cf-chl-bypass` — the legacy "I'm Under Attack" marker, kept for older Cloudflare configurations.
 *
 * ### Why `server: cloudflare` + `cf-ray` is *not* a signal
 * It used to be one here, on the theory that `cf-ray` distinguishes a Cloudflare block page from an origin
 * error passed through. It does not: **Cloudflare stamps `cf-ray` (and `server: cloudflare`) on every
 * response it proxies**, success or failure. On a fully Cloudflare-fronted host — MangaFire, most sources —
 * that made every genuine origin `403` (a bad VRF signature, an expired session, a rejected API key) look
 * like a challenge, so the app spun up a browser engine, waited out the solve timeout, and only then
 * surfaced the real error. Removing it is what makes an origin error fail fast and legibly.
 *
 * The trade is a challenge that sets *none* of the three markers, which we would now miss and report as a
 * plain `403`. No such shape has been observed; a missed challenge degrades to a clear error, whereas the
 * old false positive degraded every error into a 30-second hang.
 */
fun HttpResponse.isCloudflareChallenge(): Boolean {
    val code = status.value
    if (code != 403 && code != 503) return false

    if (headers["cf-mitigated"]?.equals("challenge", ignoreCase = true) == true) return true
    if (headers["cf-chl-bypass"] != null) return true
    return headers.getAll("server-timing").orEmpty().any(::hasChallengeRay)
}

/**
 * Whether a `Server-Timing` header value contains the `chlray` metric. The header is a comma-separated
 * list of `name;key=value` entries, so this matches on an entry's **name** rather than anywhere in the
 * string — a `desc` that merely happened to contain the word shouldn't count.
 */
private fun hasChallengeRay(serverTiming: String): Boolean =
    serverTiming
        .splitToSequence(',')
        .any { entry -> entry.substringBefore(';').trim().equals("chlray", ignoreCase = true) }