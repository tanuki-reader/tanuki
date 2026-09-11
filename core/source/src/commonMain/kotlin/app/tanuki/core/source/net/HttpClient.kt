package app.tanuki.core.source.net

import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.source.net.cloudflare.CloudflareChallengeSolver
import app.tanuki.core.source.net.cloudflare.CloudflareCookieStore
import app.tanuki.core.source.net.cloudflare.cloudflarePlugin
import app.tanuki.core.source.net.cloudflare.isCloudflareChallenge
import app.tanuki.core.source.net.cookie.SourceCookieJar
import app.tanuki.core.source.net.cookie.sourceCookiePlugin
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.compression.UnsupportedContentEncodingException
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CancellationException

/**
 * The shared HTTP client — one per process, every source request (and the cover/page image loader) flows
 * through it. Ktor on every platform; only the engine differs (OkHttp on Android, Darwin on iOS), which
 * is all the `actual` supplies. The **Cloudflare interceptor and the cookie jar are installed here, in
 * common code** ([installSourcePlugins]), so the bypass, the `cf_clearance` replay and session cookies
 * behave identically on both platforms and no source can sidestep them.
 *
 * @param solver platform browser-engine challenge solver, injected per platform.
 * @param cookieStore per-host clearance cache shared across requests.
 * @param cookieJar per-host jar for the sites' own cookies.
 * @param userAgent supplies the one `User-Agent` all source traffic goes out with.
 * @param logger records challenge/solve outcomes; the only view into a bypass that silently did nothing.
 */
expect fun platformSourceHttpClient(
    solver: CloudflareChallengeSolver,
    cookieStore: CloudflareCookieStore,
    cookieJar: SourceCookieJar,
    userAgent: SourceUserAgentProvider,
    logger: Logger,
): HttpClient

/**
 * Common client configuration, applied by every platform `actual` so the two clients differ only by
 * engine. Turns off `expectSuccess` — the Cloudflare interceptor inspects `403`/`503` responses itself to
 * detect a challenge, so Ktor must not throw on them first — and installs the plugins below.
 *
 * **Install order is load-bearing**: Ktor runs `Send` interceptors in installation order, each wrapping
 * the next, so the first one installed ends up outermost and the last one innermost:
 * ```
 * timeout            transport deadlines for every physical attempt
 *   └─ retry          one retry envelope per logical call
 *        └─ cloudflare  challenge detected and replayed, once
 *             └─ cookie jar  right against the engine, sees every physical attempt
 *                  └─ engine
 * ```
 *  - **Timeouts outermost** so their deadlines describe the whole call rather than one attempt of it.
 *    Only [connectTimeout]/[socketTimeout] are set client-wide; see [installSourceTimeouts] for why
 *    there is deliberately no client-wide *request* timeout.
 *  - **Retry above Cloudflare** because [HttpRequestRetry] copies the request builder per attempt, so
 *    sitting outside is what lets each attempt re-derive the clearance and the jar's cookies from
 *    scratch instead of replaying a header built for the attempt that just failed.
 *  - **The cookie jar innermost**, unchanged — see [sourceCookiePlugin] for why that is the position it
 *    has to have.
 *
 * The user-agent and content-encoding plugins are exempt from that reasoning: they hook the request and
 * receive pipelines rather than `Send`, so where they sit in this list is irrelevant to the ladder (see
 * [sourceUserAgentPlugin] and [installSourceEncoding] — the latter has an ordering rule of its own, but
 * only against the plugin installed beside it).
 *
 * One consequence of that worth stating rather than re-deriving: Ktor runs the **receive** pipeline from
 * inside `HttpSendPipeline.Receive`, i.e. *within* the `Send` chain, so decoding happens **per physical
 * attempt** — a Cloudflare challenge body and every retried response are each decoded and thrown away.
 * That is free here only because nothing in the ladder reads a body: [isCloudflareChallenge] decides from
 * headers alone, and the retry predicate from status and headers.
 */
fun HttpClientConfig<*>.installSourcePlugins(
    solver: CloudflareChallengeSolver,
    cookieStore: CloudflareCookieStore,
    cookieJar: SourceCookieJar,
    userAgent: SourceUserAgentProvider,
    logger: Logger,
) {
    expectSuccess = false
    installSourceTimeouts()
    installSourceRetries()
    installSourceEncoding()
    install(sourceUserAgentPlugin(userAgent))
    install(cloudflarePlugin(solver, cookieStore, logger))
    install(sourceCookiePlugin(cookieJar))
}

/**
 * Transport deadlines for source traffic. Without these the engine defaults apply, and they differ per
 * platform and are far too generous: OkHttp has **no call timeout at all**, so a host that accepts the
 * connection and then stalls mid-body parks the request forever. That is not a theoretical cost —
 * `DownloadManager` runs one chapter at a time, so a single stalled page stalls the whole queue.
 *
 * ### Why there is no client-wide `requestTimeoutMillis`
 * Deliberate, and it should stay that way. Ktor implements the request timeout by launching a killer
 * coroutine that cancels the request's *execution context* — which spans the entire `Send` chain,
 * challenge solving included. This app's solver is interactive
 * ([app.tanuki.core.source.net.cloudflare.InteractiveCloudflareSolver]): it puts the challenge on screen
 * and waits on the **user**, for as long as they take. A client-wide ceiling would therefore cancel a
 * browse or detail call out from under someone in the middle of answering a Turnstile widget, and no
 * value is both long enough to be polite and short enough to be a useful deadline.
 *
 * A hard per-call ceiling still makes sense where a call is known never to be interactive — that is
 * applied per request, at the one call site that needs it (see [KtorPageFetcher]).
 *
 * [socketTimeout] is the one that does the work here: it bounds *inactivity* between packets, which is
 * exactly the stalled-body case, and it costs nothing on a slow-but-progressing download.
 */
private fun HttpClientConfig<*>.installSourceTimeouts() {
    install(HttpTimeout) {
        connectTimeoutMillis = CONNECT_TIMEOUT_MS
        socketTimeoutMillis = SOCKET_TIMEOUT_MS
    }
}

/**
 * Makes content encoding **explicit and uniform**, where before it was an accident of the engine.
 *
 * Neither engine was under app control: OkHttp and NSURLSession each add `Accept-Encoding: gzip` of
 * their own accord and decode the response, so compression worked without anyone asking for it — and
 * stopped working the moment a source set the header itself, which is the bug
 * [sourceAcceptEncodingPlugin] exists to close. The strip has to be installed **before**
 * [ContentEncoding], which defers to an `Accept-Encoding` that is already present; see that plugin for
 * the full argument.
 *
 * ### What is advertised, and the trade in it
 * `gzip,deflate,identity` — the whole set Ktor 3.5 can decode. There is **no brotli decoder in the Ktor
 * client at all** (`ktor-utils` ships gzip, deflate and identity and nothing else), so `br` cannot be
 * claimed honestly, and neither can `zstd`. Including `identity` is deliberate: without it a server that
 * answers an explicit `Content-Encoding: identity` would raise
 * [UnsupportedContentEncodingException] over a body that needed no decoding at all.
 *
 * The cost is that no browser sends this exact header, and the app otherwise works hard to look like one
 * (see [DEFAULT_SOURCE_USER_AGENT]). That is accepted: `Accept-Encoding` is a far weaker bot signal than
 * the UA, and the alternative trades a body we can always read for a header that merely looks better.
 *
 * ### Why the mode is left alone
 * [io.ktor.client.plugins.compression.ContentEncodingConfig.mode] defaults to `DecompressResponse` and
 * must stay there. `All` would also compress **outgoing** bodies, and a guest's `POST` body is the
 * source's business — re-encoding it is not ours to do.
 */
private fun HttpClientConfig<*>.installSourceEncoding() {
    install(sourceAcceptEncodingPlugin())
    install(ContentEncoding) {
        gzip()
        deflate()
        identity()
    }
}

/**
 * Retries transient faults for every source request — API calls, reader pages and covers alike. Before
 * this, the only retry anywhere was the hand-rolled loop inside
 * [app.tanuki.core.domain.download.usecase.DownloadManager]; everything else gave up on the first dropped
 * connection or `502`.
 *
 * Two rules keep it from doing harm:
 *
 * 1. **Idempotent methods only.** A `GET`/`HEAD` can be replayed safely; a guest's `POST` cannot, and
 *    whether replaying it is acceptable is the source's business, not ours.
 * 2. **Never retry a Cloudflare challenge.** `expectSuccess = false`, so a challenge reaches this
 *    predicate as an ordinary `403`/`503` response — and a challenge does not clear by asking again. It
 *    is the interceptor's to answer, once, and a client-level retry would fight it: extra hits on a host
 *    that is already refusing us, and on the second pass another trip through the solve gate. This is the
 *    same exemption `DownloadManager` makes for [app.tanuki.core.domain.download.model.DownloadError]
 *    `.NeedsVerification`.
 *
 * The retryable status set mirrors `KtorPageFetcher`'s own classification, so "transient" means the same
 * thing at both layers. The one non-status exemption is [UnsupportedContentEncodingException]: a host
 * that answered in an encoding we did not ask for (see [installSourceEncoding]) will answer the same way
 * on the second ask, so retrying it buys three attempts at a guaranteed failure — the same argument
 * [isTransient] already makes for a `404`.
 */
private fun HttpClientConfig<*>.installSourceRetries() {
    install(HttpRequestRetry) {
        retryIf(maxRetries = MAX_RETRIES) { request, response ->
            request.method.isReplayable() &&
                !response.isCloudflareChallenge() &&
                response.status.value.isTransient()
        }
        retryOnExceptionIf(maxRetries = MAX_RETRIES) { request, cause ->
            request.method.isReplayable() &&
                cause !is CancellationException &&
                cause !is UnsupportedContentEncodingException
        }
        exponentialDelay(
            baseDelayMs = RETRY_BASE_DELAY_MS,
            maxDelayMs = RETRY_MAX_DELAY_MS,
            respectRetryAfterHeader = true,
        )
    }
}

/** How long to wait for a connection to be established before giving up on the host. */
private const val CONNECT_TIMEOUT_MS = 15_000L

/**
 * The longest gap of silence tolerated mid-exchange. Generous enough for a source that thinks for a while
 * before answering, short enough that a stalled download slot frees itself rather than hanging the queue.
 */
private const val SOCKET_TIMEOUT_MS = 30_000L

/**
 * Attempts *after* the first, per request. Two is enough to ride out a blip without turning a host that is
 * genuinely down into three times the traffic — and the download path stacks its own retries on top of
 * whatever happens here, which is why it opts out (see [KtorPageFetcher]).
 */
private const val MAX_RETRIES = 2

/** First backoff, doubling per attempt; Ktor adds its own jitter on top. */
private const val RETRY_BASE_DELAY_MS = 1_000L

/** Ceiling on the backoff, so the last attempt is still something a waiting screen can sit through. */
private const val RETRY_MAX_DELAY_MS = 8_000L

/** Whether a method may be sent again as-is. Conservative on purpose: only the two that read. */
private fun HttpMethod.isReplayable(): Boolean = this == HttpMethod.Get || this == HttpMethod.Head

/**
 * Whether a status is worth another attempt: a timeout, a rate limit, or a server-side fault. Everything
 * else — a `404` for a delisted chapter, a `403` from the origin, an expired signed URL — answers the same
 * way however many times it is asked.
 */
private fun Int.isTransient(): Boolean = this == 408 || this == 429 || this >= 500
