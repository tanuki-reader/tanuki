package app.tanuki.platform.web

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import app.tanuki.assets.designSystem.TanukiMotion
import app.tanuki.assets.designSystem.TanukiRadii
import app.tanuki.assets.designSystem.TanukiTheme
import app.tanuki.assets.designSystem.component.CloseIcon
import app.tanuki.assets.designSystem.component.LockIcon
import app.tanuki.assets.designSystem.component.ShieldCheckIcon
import app.tanuki.assets.designSystem.component.TanukiButton
import app.tanuki.assets.designSystem.component.TanukiButtonVariant
import app.tanuki.assets.designSystem.component.TanukiIconButton
import app.tanuki.assets.designSystem.component.TanukiLoadingIndicator
import app.tanuki.core.source.net.session.PendingSiteSession
import app.tanuki.core.source.net.session.SILENT_SESSION_CEILING
import app.tanuki.core.source.net.session.SiteSessionCompletion
import app.tanuki.core.source.net.session.SiteSessionCoordinator
import app.tanuki.core.source.net.session.SiteSessionReason
import app.tanuki.core.source.net.session.SiteSessionResult
import app.tanuki.core.source.net.session.isSatisfiedBy
import app.tanuki.core.source.net.session.silentBudget
import app.tanuki.platform.rememberReducedMotion
import app.tanuki.shared.resources.Res
import app.tanuki.shared.resources.site_session_cancel
import app.tanuki.shared.resources.site_session_challenge_message
import app.tanuki.shared.resources.site_session_challenge_title
import app.tanuki.shared.resources.site_session_close
import app.tanuki.shared.resources.site_session_connecting
import app.tanuki.shared.resources.site_session_done
import app.tanuki.shared.resources.site_session_login_message
import app.tanuki.shared.resources.site_session_login_title
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.time.TimeSource

/** Height of the layer's own bar, matching the design system's app-bar height. */
private val SessionBarHeight = 56.dp

/**
 * Draw order for a revealed layer: above the whole app, since it is now the thing the user must deal with.
 */
private const val RevealedZIndex = 1f

/**
 * Draw order while the challenge solves out of sight: **below** the app's own UI, which is opaque and
 * therefore hides it completely. Below, not invisible — the engine still has to paint to solve anything.
 */
private const val SilentZIndex = -1f

/** The reason badge's a tinted plate — big enough to read as a plate, small enough to sit on a text line. */
private val ReasonBadgeSize = 32.dp

/** How far the layer travels on enter/exit, as a fraction of its height — a lift, not a full sheet slide. */
private const val SLIDE_FRACTION = 24

/**
 * Renders whatever browser session [coordinator] is asking for, as a full-screen layer at the app root.
 *
 * Hosted at the app root rather than being a navigation destination, because the caller is a Ktor plugin
 * suspended mid-request with no relationship to the back stack — it can be a browse fetch, a cover load,
 * or a page prefetch three screens deep. A route would need every one of those to know how to navigate and
 * how to get back; a layer driven by the coordinator's state needs none of that.
 *
 * While mounted it registers with the coordinator, which is how network code tells "the user declined"
 * apart from "no UI existed to ask" — a background library update must fail fast rather than park on a
 * screen nobody is looking at.
 *
 * ### Silent first
 *
 * A Cloudflare challenge is **not** shown straight away. The layer is composed the moment the session
 * arrives — full-size, laid out, and painting, which is all the engine needs to work through a challenge —
 * but it is z-ordered *underneath* the app, so the user goes on reading while it solves. Most challenges
 * are non-interactive and clear in a few seconds; those now happen entirely out of sight.
 *
 * It is put on screen only when the challenge is still up after its [silentBudget] — that is, when it has
 * escalated to something only a person can finish. A login is shown immediately, since there is nothing to
 * solve on the user's behalf.
 *
 * What makes this safe is that **occlusion is not the same as being headless**. The deleted offscreen
 * solvers failed because their engine was zero-size and never attached, so it never rasterized and
 * Turnstile never ran (see `decisions.md`, 2026-07-30). Being covered by something drawn in front happens
 * in the platform compositor and is invisible to the page. Hence the rules this composable keeps: while
 * silent the layer is full-size and fully opaque, and *only* its draw order changes. Never fade it, never
 * shrink it, never take it out of the composition to hide it.
 *
 * @param onRevealedChange told whether the layer must be on top of the app, for a host that stacks it
 *   in a separate view (iOS's SwiftUI root). Inside one Compose tree the zIndex does this, and the
 *   default ignores it.
 */
@Composable
fun SiteSessionHost(
    coordinator: SiteSessionCoordinator,
    onRevealedChange: (Boolean) -> Unit = {},
) {
    DisposableEffect(coordinator) {
        coordinator.attach()
        onDispose { coordinator.detach() }
    }

    val pending by coordinator.pending.collectAsStateWithLifecycle()

    // The session being rendered. Held separately from `pending` because the exit animation still needs a
    // session to draw after the coordinator has cleared it.
    var shown by remember { mutableStateOf<PendingSiteSession?>(null) }
    // Which session has been put on screen — an identity, not a flag, so a new session starts silent by
    // construction while one that is on its way out stays revealed for the whole of its exit.
    var revealedSession by remember { mutableStateOf<PendingSiteSession?>(null) }

    pending?.let { session ->
        if (shown !== session) {
            shown = session
            // A login has nothing to solve out of sight — the user *is* the mechanism. Revealing it here
            // rather than from an effect inside the layer keeps it from spending a frame behind the app.
            if (session.request.reason != SiteSessionReason.CLOUDFLARE_CHALLENGE) revealedSession = session
        }
    }

    val session = shown ?: return
    val revealed = revealedSession === session

    val reduceMotion = rememberReducedMotion()
    val enterMillis = if (reduceMotion) TanukiMotion.reducedMillis else TanukiMotion.screenEnterMillis
    val exitMillis = if (reduceMotion) TanukiMotion.reducedMillis else TanukiMotion.screenExitMillis

    // How present the layer is *visually*: 0 while it solves silently, 1 once revealed, animated between —
    // the same lift-and-fade every screen transition uses, since it still arrives unannounced in the middle
    // of whatever the user was doing. Deliberately **not** an `AnimatedVisibility`: that would keep the
    // layer out of the composition until it was revealed, and an engine that isn't composed can't solve
    // anything. This drives a `graphicsLayer` that is only applied once revealed (see the layer).
    val onScreen = pending != null && revealed
    val presence by animateFloatAsState(
        targetValue = if (onScreen) 1f else 0f,
        animationSpec =
            tween(
                durationMillis = if (onScreen) enterMillis else exitMillis,
                easing = if (onScreen) TanukiMotion.EaseEnter else TanukiMotion.EaseExit,
            ),
        label = "siteSessionPresence",
    )

    // For a host that stacks this layer itself instead of relying on the zIndex below. iOS's SwiftUI root
    // keeps the layer in its own view behind the app and needs to know when to bring it to the front.
    // True from the reveal until the exit animation has finished.
    val onTop = revealed && (pending != null || presence > 0f)
    LaunchedEffect(onTop) { onRevealedChange(onTop) }

    // Composed while a session is live, and kept for as long as an exit animation is still running. A
    // session dismissed while still silent was never visible, so `presence` is already 0 and it simply goes.
    if (pending != null || presence > 0f) {
        SiteSessionLayer(
            session = session,
            revealed = revealed,
            presence = presence,
            reduceMotion = reduceMotion,
            onReveal = { revealedSession = session },
        )
    }
}

/**
 * @param revealed whether the layer is on screen for the user, as opposed to solving underneath the app.
 * @param presence how far through the reveal (or exit) animation the layer is, 0..1. Applied **only** when
 *   [revealed] — a silent layer must stay fully opaque, since an engine that isn't rasterized doesn't solve.
 * @param onReveal called when the challenge has outlived its silent budget and must be shown. Idempotent.
 */
@Composable
private fun SiteSessionLayer(
    session: PendingSiteSession,
    revealed: Boolean,
    presence: Float,
    reduceMotion: Boolean,
    onReveal: () -> Unit,
) {
    val request = session.request
    // The latest snapshot the browser reported. Held so the confirm action has something to hand back for
    // a login, where no predicate can decide on the user's behalf.
    var snapshot by remember(session) { mutableStateOf(SiteSessionResult(emptyMap(), null)) }
    // The cookies the browser already had before this session loaded anything. Platform cookie stores are
    // shared and persistent, so a stale cf_clearance is usually sitting there — see isSatisfiedBy.
    val baseline = remember(session) { mutableStateOf<Map<String, String>?>(null) }

    // Back dismisses the session rather than navigating the screen underneath, which would leave the user
    // looking at a layer they can't reach the app behind. Only while revealed: a silent layer is not what
    // the back gesture is aimed at, and swallowing it would break the app the user is still using.
    if (revealed) {
        val backState = rememberNavigationEventState(NavigationEventInfo.None)
        NavigationBackHandler(state = backState, onBackCompleted = { session.cancel() })
    }

    val isChallenge = request.reason == SiteSessionReason.CLOUDFLARE_CHALLENGE

    // The silent budget is spent from when the session opened, not from when the challenge kind became
    // known — the clock is the user's patience, and it started the moment their request stalled.
    val opened = remember(session) { TimeSource.Monotonic.markNow() }
    var budgetSpent by remember(session) { mutableStateOf(false) }

    // Re-armed whenever the probe reports a different kind, which is how a challenge that comes back
    // `interactive` cuts its own wait to nothing instead of sitting out a budget it can never satisfy.
    LaunchedEffect(session, snapshot.challengeType) {
        if (revealed) return@LaunchedEffect
        val remaining = snapshot.challengeType.silentBudget - opened.elapsedNow()
        if (remaining.isPositive()) delay(remaining)
        budgetSpent = true
    }

    // Reveal only if there is still a challenge on screen when the budget runs out. A page that has moved
    // on has nothing for the user to do, and the session is about to end on its own.
    LaunchedEffect(budgetSpent, snapshot.onChallengePage) {
        if (budgetSpent && snapshot.onChallengePage) onReveal()
    }

    // The backstop for a session that resolves neither way — see SILENT_SESSION_CEILING. Cancelled with the
    // layer, so it only ever fires for a session that is genuinely still stuck.
    LaunchedEffect(session) {
        delay(SILENT_SESSION_CEILING)
        onReveal()
    }
    val titleResource =
        if (isChallenge) Res.string.site_session_challenge_title else Res.string.site_session_login_title
    val messageResource =
        if (isChallenge) Res.string.site_session_challenge_message else Res.string.site_session_login_message
    val dismissResource = if (isChallenge) Res.string.site_session_close else Res.string.site_session_cancel
    val dismissLabel = stringResource(dismissResource)

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                // The one thing that changes while the layer is silent: it draws *under* the app instead of
                // over it. Draw order only — never alpha, never a smaller box, never dropping out of the
                // composition. An engine that isn't rasterized is a headless engine, and a headless engine
                // is what could not solve a managed challenge in the first place.
                .zIndex(if (revealed) RevealedZIndex else SilentZIndex)
                .then(
                    if (revealed) {
                        Modifier.graphicsLayer {
                            alpha = presence
                            // A lift into place rather than a cut, matching every screen transition.
                            translationY = if (reduceMotion) 0f else (1f - presence) * size.height / SLIDE_FRACTION
                        }
                    } else {
                        Modifier
                    }
                ).then(
                    // Skipped while silent. The layer is under an opaque app, so this fill is invisible —
                    // but it is a full-screen draw on every frame for as long as the challenge takes, and
                    // the engine underneath is already the expensive thing on that frame.
                    if (revealed) Modifier.background(MaterialTheme.colorScheme.background) else Modifier
                )
                // Swallow every gesture that isn't the web view's. This is a layer over a live NavHost, so
                // without it a tap on the header would fall through and drive the screen behind. Only while
                // revealed — a silent layer must let every gesture through to the app the user is using.
                .then(
                    if (revealed) {
                        Modifier.pointerInput(session) {
                            awaitPointerEventScope { while (true) awaitPointerEvent() }
                        }
                    } else {
                        // Nothing here is for the user yet, so keep the whole subtree out of the
                        // accessibility tree rather than letting a screen reader wander into an
                        // invisible browser.
                        Modifier.clearAndSetSemantics {}
                    }
                ).safeDrawingPadding()
    ) {
        // The chrome is what makes the layer *the user's problem*, so it exists only once revealed. Written
        // as conditional siblings of the browser rather than a separate layout for the silent case: the web
        // view has to stay at the same call site across the reveal, or it would be rebuilt and the
        // half-finished challenge thrown away.
        if (revealed) {
            // Bar: the dismiss affordance leads, as it does on every pushed screen — but an X rather than a
            // back arrow, because this layer isn't on the back stack and closing it doesn't go anywhere.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().height(SessionBarHeight).padding(horizontal = TanukiTheme.spacing.sm)
            ) {
                TanukiIconButton(
                    onClick = { session.cancel() },
                    modifier = Modifier.semantics { contentDescription = dismissLabel }
                ) { tint -> CloseIcon(tint = tint, size = 22.dp) }
                Text(
                    text = stringResource(titleResource),
                    style = TanukiTheme.typography.title,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = TanukiTheme.spacing.sm)
                )
            }

            // Why the user is looking at a browser, next to an accent-tinted plate carrying the reason's
            // glyph. The one spot of orange on the layer, which is the whole accent budget it needs.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(TanukiTheme.spacing.md),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            start = TanukiTheme.spacing.gutter,
                            end = TanukiTheme.spacing.gutter,
                            bottom = TanukiTheme.spacing.lg
                        )
            ) {
                ReasonBadge(isChallenge = isChallenge)
                Text(
                    text = stringResource(messageResource, request.host),
                    style = TanukiTheme.typography.body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }

        // The site's own surface, hairline-separated from the app's chrome above and below so it is
        // visually obvious where Tanuki stops and the site begins. Full-bleed rather than inset in a card:
        // a Turnstile widget (and a login form under a keyboard) wants every pixel of height it can get.
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    // As above: no point filling a surface nobody can see, once per frame, while the
                    // browser underneath solves.
                    .then(
                        if (revealed) Modifier.background(MaterialTheme.colorScheme.surface) else Modifier
                    )
        ) {
            SiteSessionWebView(
                url = request.url,
                // Expire the cookie we're waiting on before loading, so the browser must genuinely re-solve.
                // Without this Cloudflare accepts its own stale clearance in the browser, issues no new one,
                // and the session waits forever for a change that never comes.
                clearCookie = (request.completion as? SiteSessionCompletion.WhenCookieSet)?.cookieName,
                // While silent the engine is under the app; a tap that reached it would land on a browser
                // the user can't see. Revealing hands the touches back.
                interactive = revealed,
                onSnapshot = { latest ->
                    snapshot = latest
                    val before = baseline.value
                    if (before == null) {
                        // First report, taken before the page has loaded: whatever is here is left over from a
                        // previous session and is known-bad, since a request carrying it was just challenged.
                        baseline.value = latest.cookies
                    } else if (request.completion.isSatisfiedBy(before, latest)) {
                        // A challenge that clears without interaction (or the moment the user taps the
                        // checkbox) ends here, so the layer disappears on its own. Resolving twice is a no-op.
                        session.finish(latest)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Cover the browser whenever a *challenge* is not on screen. The engine has to keep rendering
            // to solve one, so the view stays composed and laid out underneath — this only hides it.
            // Without it the user watches the site load, and on an API host that means a screenful of raw
            // JSON in the moment between the challenge clearing and the layer closing.
            //
            // Gated on the reason, not just the flag: a login page carries no `_cf_chl_opt`, so covering on
            // the flag alone would hide the very form the user is here to fill in.
            //
            // And gated on `revealed`, which is not merely an optimisation: the cover carries an
            // indefinite progress indicator, and an indefinite animation invalidates *every* frame for as
            // long as it is composed. Left ungated it would drive the whole app at full frame rate to
            // animate a spinner nobody can see, on exactly the frames the browser engine is busiest.
            if (revealed) {
                Crossfade(
                    targetState = isChallenge && !snapshot.onChallengePage,
                    animationSpec = tween(TanukiMotion.screenEnterMillis, easing = TanukiMotion.EaseEnter)
                ) { covered ->
                    if (covered) ConnectingCover(host = request.host)
                }
            }
        }

        // A footer exists only where there is something to confirm. A login has no cookie that reliably
        // means "signed in", so the user says when it's done; a challenge resolves itself and gets no
        // action bar at all, handing that height to the challenge widget. Dismissing is the bar's X in
        // both cases — repeating it down here would be two controls doing one job.
        if (request.completion == SiteSessionCompletion.WhenUserConfirms) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        // Gutter horizontally so the action's trailing edge lines up with the message
                        // text's leading gutter above, rather than floating a few dp further out.
                        .padding(horizontal = TanukiTheme.spacing.gutter, vertical = TanukiTheme.spacing.md),
                horizontalArrangement = Arrangement.spacedBy(TanukiTheme.spacing.sm, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TanukiButton(
                    text = stringResource(Res.string.site_session_done),
                    onClick = { session.finish(snapshot) },
                    variant = TanukiButtonVariant.Filled
                )
            }
        }
    }
}

/** The accent-tinted plate carrying the session reason's glyph: a shield for a check, a padlock for a login. */
@Composable
private fun ReasonBadge(isChallenge: Boolean) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .size(ReasonBadgeSize)
                .clip(TanukiRadii.chipShape)
                .background(TanukiTheme.colors.accentTint)
    ) {
        val tint = TanukiTheme.colors.accent
        if (isChallenge) ShieldCheckIcon(tint = tint) else LockIcon(tint = tint)
    }
}

/**
 * What sits over the browser while the challenge page isn't showing — before it loads, and in the moment
 * after it clears while the layer closes. The brand indicator over a plain status line, so a wait that the
 * user did not ask for at least says what it is waiting on.
 */
@Composable
private fun ConnectingCover(host: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(TanukiTheme.spacing.lg, Alignment.CenterVertically),
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                // The cover is the thing on top; nothing below it should receive a stray tap.
                .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
                .padding(horizontal = TanukiTheme.spacing.gutter)
    ) {
        TanukiLoadingIndicator()
        Text(
            text = stringResource(Res.string.site_session_connecting, host),
            style = TanukiTheme.typography.caption,
            color = TanukiTheme.colors.textFaint,
            textAlign = TextAlign.Center
        )
    }
}