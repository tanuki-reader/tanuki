package app.tanuki.core.source.net.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The bridge between network code that needs a browser session and the UI that can show one.
 *
 * Some things a site demands can only be done *by the person using the app*: tapping through a Cloudflare
 * managed challenge, or signing in to a source that requires an account. Both are the same shape — put a
 * real browser on screen, pointed at a URL, and harvest the cookies it ends up holding — so both go
 * through here rather than each growing its own mechanism.
 *
 * The awkward part this exists to solve: the caller is a Ktor plugin running on a background coroutine
 * deep inside a request, and it must **suspend until the user is done**. It cannot navigate, and the UI
 * cannot call it. So the caller [request]s and awaits; the UI observes [pending], renders the session, and
 * calls [PendingSiteSession.finish] or [PendingSiteSession.cancel] when it resolves.
 *
 * Compose-free by design (it lives in `core/source`, below the UI) — [pending] is a plain [StateFlow] the
 * overlay collects.
 *
 * One session at a time: the [Mutex] queues a second caller until the first is resolved, so two challenged
 * hosts can't fight over the screen. Cloudflare requests are already single-flighted per host upstream by
 * `HostSolveGate`, so in practice this only serialises genuinely different sites.
 */
class SiteSessionCoordinator {
    private val turn = Mutex()
    private val _pending = MutableStateFlow<PendingSiteSession?>(null)

    /** The session the UI should be showing, or `null` when there is nothing to show. */
    val pending: StateFlow<PendingSiteSession?> = _pending.asStateFlow()

    private val hosts = MutableStateFlow(0)

    /**
     * Whether any UI is currently able to show a session. [request] short-circuits when nothing is
     * attached, which is the difference between "the user declined" and "there was no one to ask" —
     * a library update running in the background must fail fast, not park a coroutine for minutes
     * waiting for a screen that will never appear.
     */
    val isAttached: Boolean get() = hosts.value > 0

    /** Called by the UI host while it is composed. Balanced by [detach]. */
    fun attach() {
        hosts.value += 1
    }

    /** Called by the UI host when it leaves the composition. Any session in flight is cancelled. */
    fun detach() {
        hosts.value -= 1
        if (hosts.value <= 0) _pending.value?.cancel()
    }

    /**
     * Shows [request] to the user and suspends until they resolve it.
     *
     * @return the cookies and user agent the browser ended up with, or `null` if the user dismissed it,
     *   no UI was attached to ask, or the calling coroutine was cancelled.
     */
    suspend fun request(request: SiteSessionRequest): SiteSessionResult? {
        if (!isAttached) return null
        return turn.withLock {
            val session = PendingSiteSession(request)
            _pending.value = session
            try {
                session.await()
            } finally {
                // Covers the normal path and cancellation of the *caller* (a reader screen closing mid
                // challenge): either way the overlay must come down rather than strand the user in it.
                _pending.value = null
            }
        }
    }
}

/**
 * One session being shown. The UI reads [request] to know what to load, then resolves it exactly once —
 * later calls are ignored, so a completion predicate firing at the same moment the user hits close cannot
 * resume the caller twice.
 */
class PendingSiteSession(val request: SiteSessionRequest) {
    private val outcome = CompletableDeferred<SiteSessionResult?>()

    /** The user (or the completion predicate) resolved the session successfully. */
    fun finish(result: SiteSessionResult) {
        outcome.complete(result)
    }

    /** The user dismissed the session, or the UI hosting it went away. */
    fun cancel() {
        outcome.complete(null)
    }

    internal suspend fun await(): SiteSessionResult? = outcome.await()
}

/**
 * What to show, and how to know when it's over.
 *
 * @property url the page the browser loads — the challenged URL, or a source's login page.
 * @property host the host whose cookies the caller cares about.
 * @property completion how the session ends; see [SiteSessionCompletion].
 * @property reason what to tell the user this is for.
 */
data class SiteSessionRequest(
    val url: String,
    val host: String,
    val completion: SiteSessionCompletion,
    val reason: SiteSessionReason,
)

/** How a [SiteSessionRequest] is known to be finished. */
sealed interface SiteSessionCompletion {
    /**
     * Finished when [cookieName] takes a **new** value *and* the browser has left the interstitial. The UI
     * polls and dismisses itself, so a challenge that solves without interaction is over before the user
     * reacts to it.
     *
     * Both halves are load-bearing, and each was learned the hard way. Cloudflare sets `cf_clearance`
     * *during* the challenge, before the round trip that commits it server-side — finishing on the cookie
     * alone replayed a clearance that wasn't live yet (observed 48ms after it appeared) and got
     * re-challenged, costing the user a second challenge. And the value must be *new*, because platform
     * cookie stores are shared and persistent, so a stale clearance is usually already sitting there.
     */
    data class WhenCookieSet(val cookieName: String) : SiteSessionCompletion

    /**
     * Only the user can say when it's done — a login, where there is no single cookie that reliably means
     * "signed in". The UI shows a confirm action and returns whatever cookies exist at that point.
     */
    data object WhenUserConfirms : SiteSessionCompletion
}

/**
 * Whether the browser's [current] state finishes this session, given the cookie [baseline] it started with.
 *
 * The baseline is what makes this correct rather than merely plausible. A platform cookie store is
 * **shared and persistent** — Android's `CookieManager` outlives the process — so a `cf_clearance` from an
 * earlier run is usually already sitting there when the session opens, and a bare "is the cookie present?"
 * check passes instantly, before the page has even loaded. The caller then replays a dead cookie and is
 * re-challenged, which looks exactly like a challenge that failed to solve.
 *
 * Requiring a *changed* value is not a heuristic: this session only exists because a request carrying
 * whatever was already in the store came back challenged. That cookie is known-bad by construction, so
 * only a new one can mean anything.
 */
fun SiteSessionCompletion.isSatisfiedBy(
    baseline: Map<String, String>,
    current: SiteSessionResult,
): Boolean =
    when (this) {
        is SiteSessionCompletion.WhenCookieSet -> {
            val value = current.cookies[cookieName]
            value != null && value != baseline[cookieName] && !current.onChallengePage
        }
        // Nothing observable decides a login; the user's confirm action does.
        SiteSessionCompletion.WhenUserConfirms -> false
    }

/** Why the session is being shown; the UI turns this into localized copy. */
enum class SiteSessionReason {
    /** A Cloudflare challenge the headless solver could not clear. */
    CLOUDFLARE_CHALLENGE,

    /** The source needs the user signed in. */
    LOGIN,
}

/**
 * What the browser ended up holding.
 *
 * @property cookies every cookie the browser has for the request's host, by name.
 * @property userAgent the UA the browser actually presented (`navigator.userAgent`). Carried because a
 *   `cf_clearance` is bound to it and every replay must match; `null` if it couldn't be read.
 * @property headers other request headers that identify this browser — the Chromium user-agent client
 *   hints. Part of what Cloudflare binds a clearance to, so a caller replaying the cookies must send these
 *   too. Empty on WebKit, which has no client hints.
 * @property onChallengePage whether the browser is *currently sitting on a Cloudflare interstitial*,
 *   detected by the `window._cf_chl_opt` object those pages define. This is what tells a half-finished
 *   challenge from a finished one: the `cf_chl_*` cookies are no use for it, because they persist in the
 *   jar long after the flow completes.
 */
data class SiteSessionResult(
    val cookies: Map<String, String>,
    val userAgent: String?,
    val headers: Map<String, String> = emptyMap(),
    val onChallengePage: Boolean = false,
)