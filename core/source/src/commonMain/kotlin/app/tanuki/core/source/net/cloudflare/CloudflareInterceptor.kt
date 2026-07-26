package app.tanuki.core.source.net.cloudflare

import io.ktor.client.plugins.api.ClientPlugin
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.HttpHeaders

/**
 * The Cloudflare interceptor, as a Ktor client plugin installed on the app's **one** shared source
 * client (see [app.tanuki.core.source.net.platformSourceHttpClient]). Because every source request — and,
 * later, the cover/page image loader — goes through that client, the bypass applies uniformly and a
 * source **cannot** opt out of it (guests have no socket of their own; see CLAUDE.md → Extension System).
 *
 * Flow, per the design in CLAUDE.md → Cloudflare Bypass:
 * ```
 * request ──▶ attach any stored cf_clearance for the host
 *          ──▶ send
 *              ├─ not a challenge ──▶ return response
 *              └─ CF challenge (403/503) ──▶ solver opens a WebView, extracts cf_clearance
 *                                        ──▶ store it, re-attach, send once more ──▶ return
 * ```
 *
 * It retries **at most once** per call: if the replay is itself challenged the clearance is dropped and
 * the challenged response is returned, so a hard block can't spin the WebView in a loop. The solver is
 * independent of the WASM loader — this file knows nothing about sources.
 *
 * @param solver the platform browser-engine challenge solver (Android `WebView` / iOS `WKWebView`).
 * @param store per-host clearance cache, so a host is solved once and replayed thereafter.
 */
fun cloudflarePlugin(
    solver: CloudflareChallengeSolver,
    store: CloudflareCookieStore,
): ClientPlugin<Unit> =
    createClientPlugin("CloudflareInterceptor") {
        on(Send) { request ->
            applyClearance(request, store)
            val call = proceed(request)
            if (!call.response.isCloudflareChallenge()) return@on call

            val host = request.url.host
            val solved =
                solver.solve(
                    CloudflareChallenge(
                        url = request.url.buildString(),
                        userAgent = request.headers[HttpHeaders.UserAgent],
                    ),
                )
                    // Unsolved (offline / non-interactive block / a challenge kind we don't handle):
                    // surface the original challenge response rather than a fabricated success.
                    ?: return@on call

            store.put(host, solved)
            applyClearance(request, store)
            val replay = proceed(request)
            // The replay was challenged too → the fresh cookie is already stale (IP change, expiry).
            // Drop it so the next call solves anew instead of replaying a dead cookie, and return what we got.
            if (replay.response.isCloudflareChallenge()) store.clear(host)
            replay
        }
    }

/**
 * Attaches the stored clearance for the request's host — the `cf_clearance` cookie and the user agent it
 * is bound to — replacing rather than appending so a retry never stacks duplicate cookies. A no-op when no
 * clearance is stored (the common, unchallenged case). The UA is set only when the solver reported one;
 * see [CfClearance.userAgent].
 */
private suspend fun applyClearance(
    request: HttpRequestBuilder,
    store: CloudflareCookieStore,
) {
    val clearance = store.get(request.url.host) ?: return

    val cookieHeader = clearance.cookies.entries.joinToString("; ") { (name, value) -> "$name=$value" }
    request.headers.remove(HttpHeaders.Cookie)
    request.headers.append(HttpHeaders.Cookie, cookieHeader)

    clearance.userAgent?.let { userAgent ->
        request.headers.remove(HttpHeaders.UserAgent)
        request.headers.append(HttpHeaders.UserAgent, userAgent)
    }
}