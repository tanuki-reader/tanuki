package app.tanuki.core.source.net.cloudflare

import io.ktor.client.plugins.api.ClientPlugin
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
 * Solving is **single-flight per host** ([HostSolveGate]). Requests arrive in bursts — the reader alone
 * prefetches a window of pages in parallel — and a burst against a fresh host is challenged *together*,
 * before any of them has a clearance to attach. Without the gate each one opens its own browser engine to
 * answer the same challenge; on iOS every `WKWebView` is a separate `WebContent` process, so a single
 * chapter turn could spawn several at tens of MB apiece. Now the first request solves and the rest wait,
 * then replay the cookie it stored.
 *
 * @param solver the platform browser-engine challenge solver (Android `WebView` / iOS `WKWebView`).
 * @param store per-host clearance cache, so a host is solved once and replayed thereafter.
 */
fun cloudflarePlugin(
    solver: CloudflareChallengeSolver,
    store: CloudflareCookieStore,
): ClientPlugin<Unit> =
    createClientPlugin("CloudflareInterceptor") {
        val solves = HostSolveGate()
        on(Send) { request ->
            val attempted = applyClearance(request, store)
            val call = proceed(request)
            if (!call.response.isCloudflareChallenge()) return@on call

            val host = request.url.host
            val solved =
                solves.forHost(host) {
                    // Whoever held the gate before us may have already solved this host. Their clearance
                    // is by definition not the one our request was just challenged with, so a stored
                    // clearance that differs is a fresh solve to reuse — not another WebView to open.
                    val current = store.get(host)
                    if (current != null && current != attempted) {
                        current
                    } else {
                        solver
                            .solve(
                                CloudflareChallenge(
                                    url = request.url.buildString(),
                                    userAgent = request.headers[HttpHeaders.UserAgent],
                                ),
                            )?.also { store.put(host, it) }
                    }
                }
                    // Unsolved (offline / non-interactive block / a challenge kind we don't handle):
                    // surface the original challenge response rather than a fabricated success.
                    ?: return@on call

            applyClearance(request, store)
            val replay = proceed(request)
            // The replay was challenged too → the fresh cookie is already stale (IP change, expiry).
            // Drop it so the next call solves anew instead of replaying a dead cookie, and return what we got.
            if (replay.response.isCloudflareChallenge()) store.clear(host)
            replay
        }
    }

/**
 * Serializes challenge solving per host, so concurrent requests to one host answer one challenge between
 * them instead of one each. Only *solving* is serialized — unchallenged traffic never reaches the gate, and
 * two different hosts are solved in parallel.
 *
 * The per-host locks are created under one guard lock and never removed: hosts are few (a handful of
 * sources and their CDNs) and a `Mutex` is tiny, so reclaiming them would cost more complexity than the
 * memory is worth.
 */
private class HostSolveGate {
    private val guard = Mutex()
    private val locks = mutableMapOf<String, Mutex>()

    suspend fun <T> forHost(
        host: String,
        block: suspend () -> T
    ): T = guard.withLock { locks.getOrPut(host) { Mutex() } }.withLock { block() }
}

/**
 * Attaches the stored clearance for the request's host — the `cf_clearance` cookie and the user agent it
 * is bound to — replacing rather than appending so a retry never stacks duplicate cookies. A no-op when no
 * clearance is stored (the common, unchallenged case). The UA is set only when the solver reported one;
 * see [CfClearance.userAgent].
 *
 * @return the clearance it attached, or `null` if there was none — which is what the caller compares
 *   against later to tell "nobody has solved this host" from "someone solved it while I was queued".
 */
private suspend fun applyClearance(
    request: HttpRequestBuilder,
    store: CloudflareCookieStore,
): CfClearance? {
    val clearance = store.get(request.url.host) ?: return null

    val cookieHeader = clearance.cookies.entries.joinToString("; ") { (name, value) -> "$name=$value" }
    request.headers.remove(HttpHeaders.Cookie)
    request.headers.append(HttpHeaders.Cookie, cookieHeader)

    clearance.userAgent?.let { userAgent ->
        request.headers.remove(HttpHeaders.UserAgent)
        request.headers.append(HttpHeaders.UserAgent, userAgent)
    }
    return clearance
}