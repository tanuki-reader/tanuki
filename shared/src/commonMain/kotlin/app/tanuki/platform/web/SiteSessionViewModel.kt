package app.tanuki.platform.web

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.presentation.TanukiViewModel
import app.tanuki.core.source.net.session.PendingSiteSession
import app.tanuki.core.source.net.session.SILENT_SESSION_CEILING
import app.tanuki.core.source.net.session.SiteSessionCompletion
import app.tanuki.core.source.net.session.SiteSessionCoordinator
import app.tanuki.core.source.net.session.SiteSessionReason
import app.tanuki.core.source.net.session.SiteSessionResult
import app.tanuki.core.source.net.session.isSatisfiedBy
import app.tanuki.core.source.net.session.silentBudget
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * What the browser-session layer (Cloudflare challenges, logins) shows. Both platforms render it: Compose's
 * `SiteSessionHost` on Android, `SiteSessionLayer` in SwiftUI on iOS.
 */
sealed interface SiteSessionState {
    /** Nothing to show. */
    data object Idle : SiteSessionState

    /**
     * A session in flight. The browser is mounted, full size and painting, for as long as this lasts.
     *
     * @param sessionId changes with every session, so the UI builds a fresh browser for a new one and
     *   keeps the same one across every other change (rebuilding it would throw away a half-solved challenge).
     * @param clearCookie the cookie to delete for the host before loading, so the browser has to re-solve.
     * @param revealed on screen for the user, as opposed to solving underneath the app.
     * @param covered a challenge whose page isn't showing (still loading, or already cleared): the UI covers
     *   the browser with a "Connecting to…" status rather than showing the site.
     * @param needsConfirm a login: only the user can say when it's done, so the UI shows a Done action.
     */
    data class Active(
        val sessionId: Int,
        val url: String,
        val host: String,
        val isChallenge: Boolean,
        val clearCookie: String?,
        val revealed: Boolean,
        val covered: Boolean,
        val needsConfirm: Boolean,
    ) : SiteSessionState
}

/**
 * Drives the browser-session layer from [SiteSessionCoordinator]: which session is showing, whether it is
 * still solving out of sight, and when it is done.
 *
 * ### Silent first
 *
 * A Cloudflare challenge is **not** shown straight away. The UI mounts the browser the moment the session
 * arrives, but underneath the app, so the user goes on reading while it solves. It is revealed only when the
 * challenge is still up after its [silentBudget] (it has escalated to something only a person can finish),
 * or after [SILENT_SESSION_CEILING] whatever the page says. A login is revealed at once: there is nothing to
 * solve on the user's behalf.
 *
 * Extends [TanukiViewModel] directly, not `StateViewModel`: the state isn't derived from a flow but stepped
 * by timers and browser reports, and it runs between [attach] and [detach], which the UI calls while the
 * layer is mounted (the app root, so for the life of the UI).
 */
@Inject
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey
class SiteSessionViewModel(
    private val coordinator: SiteSessionCoordinator,
    dispatcher: DispatcherProvider,
    logger: Logger,
) : TanukiViewModel(dispatcher, logger) {
    val uiState: StateFlow<SiteSessionState>
        field = MutableStateFlow<SiteSessionState>(SiteSessionState.Idle)

    /** The session being shown, and what the browser has reported for it. */
    private class Live(val id: Int, val session: PendingSiteSession) {
        val request = session.request
        var snapshot = SiteSessionResult(emptyMap(), null)

        /**
         * The cookies the browser had before this session loaded anything. Platform cookie stores are shared
         * and persistent, so a stale cf_clearance is usually sitting there; see [isSatisfiedBy].
         */
        var baseline: Map<String, String>? = null
        var revealed = request.reason != SiteSessionReason.CLOUDFLARE_CHALLENGE
    }

    private var live: Live? = null
    private var nextId = 0
    private var attachment: Job? = null

    /**
     * The UI can show a session from now on. Until this is called, [SiteSessionCoordinator.request] fails
     * fast, which is how a background library update tells "the user declined" from "no one to ask".
     */
    fun attach() {
        if (attachment != null) return
        coordinator.attach()
        attachment = viewModelScope.launch { coordinator.pending.collectLatest(::show) }
    }

    /** The UI went away. Any session in flight is cancelled. */
    fun detach() {
        val job = attachment ?: return
        attachment = null
        job.cancel()
        coordinator.detach()
        live = null
        uiState.value = SiteSessionState.Idle
    }

    /**
     * The browser's latest cookies and page state, reported on a poll. The first report, taken before the
     * page has loaded, is the baseline: whatever is there is left over and known-bad, since a request
     * carrying it was just challenged. A later report that satisfies the completion ends the session, so a
     * challenge that clears on its own disappears without the user doing anything.
     */
    fun onSnapshot(result: SiteSessionResult) {
        val live = live ?: return
        live.snapshot = result
        val before = live.baseline
        if (before == null) {
            live.baseline = result.cookies
        } else if (live.request.completion.isSatisfiedBy(before, result)) {
            live.session.finish(result)
        }
        publish(live)
    }

    /** The user closed the layer. */
    fun onDismiss() {
        live?.session?.cancel()
    }

    /** The user says the login is done: hand back whatever the browser has. */
    fun onDone() {
        live?.let { it.session.finish(it.snapshot) }
    }

    override fun onCleared() {
        detach()
    }

    /** Shows [session] until the coordinator moves on, revealing it if it outlives its silent budget. */
    private suspend fun show(session: PendingSiteSession?) {
        if (session == null) {
            live = null
            uiState.value = SiteSessionState.Idle
            return
        }
        val live = Live(nextId++, session).also { live = it }
        publish(live)

        // One clock for both rules. The budget is spent from when the session opened, not from when the
        // challenge type became known: the clock is the user's patience, which started when their request
        // stalled. Re-read every tick, so a page that turns out `interactive` (zero budget) is revealed at
        // the next one rather than sitting out a budget it can never satisfy.
        var waited = Duration.ZERO
        while (!live.revealed) {
            val budgetSpent = waited >= live.snapshot.challengeType.silentBudget
            // Only if the challenge is still on screen: a page that has moved on has nothing for the user to
            // do, and the session is about to end on its own. The ceiling is the backstop for one that
            // resolves neither way.
            if ((budgetSpent && live.snapshot.onChallengePage) || waited >= SILENT_SESSION_CEILING) {
                live.revealed = true
                publish(live)
                break
            }
            delay(TICK)
            waited += TICK
        }
    }

    private fun publish(live: Live) {
        val request = live.request
        val isChallenge = request.reason == SiteSessionReason.CLOUDFLARE_CHALLENGE
        uiState.value =
            SiteSessionState.Active(
                sessionId = live.id,
                url = request.url,
                host = request.host,
                isChallenge = isChallenge,
                clearCookie = (request.completion as? SiteSessionCompletion.WhenCookieSet)?.cookieName,
                revealed = live.revealed,
                covered = isChallenge && !live.snapshot.onChallengePage,
                needsConfirm = request.completion == SiteSessionCompletion.WhenUserConfirms,
            )
    }

    private companion object {
        /** How often the silent clock is checked. Fine enough that nobody can tell, coarse enough to be free. */
        val TICK = 100.milliseconds
    }
}
