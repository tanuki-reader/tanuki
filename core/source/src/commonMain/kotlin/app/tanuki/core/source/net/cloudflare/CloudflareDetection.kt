package app.tanuki.core.source.net.cloudflare

import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders

/**
 * Whether [this] response is a Cloudflare challenge the [cloudflarePlugin] should try to solve, decided
 * from **headers only** — the body is left unread so detection never consumes a stream the caller still
 * needs.
 *
 * A challenge is a `403`/`503` that Cloudflare marks as its own. Two signals, either sufficient:
 *  - `cf-mitigated: challenge` — the explicit, modern marker Cloudflare sets on an interactive challenge.
 *  - `server: cloudflare` **with** a `cf-ray` id — a Cloudflare-served block page. The `cf-ray` guards
 *    against a plain origin `403` that merely proxies through Cloudflare without challenging.
 *
 * Deliberately conservative: a real API `403` (bad auth) from a Cloudflare-fronted host has no
 * `cf-mitigated` and — for a genuine origin error — Cloudflare passes it through, so this returns `false`
 * and the caller sees the true error rather than pointlessly opening a WebView. Tune against a live
 * MangaFire challenge; Turnstile/managed-challenge header shapes may need widening.
 */
fun HttpResponse.isCloudflareChallenge(): Boolean {
    val code = status.value
    if (code != 403 && code != 503) return false

    if (headers["cf-mitigated"]?.equals("challenge", ignoreCase = true) == true) return true

    val servedByCloudflare = headers[HttpHeaders.Server]?.contains("cloudflare", ignoreCase = true) == true
    return servedByCloudflare && headers["cf-ray"] != null
}