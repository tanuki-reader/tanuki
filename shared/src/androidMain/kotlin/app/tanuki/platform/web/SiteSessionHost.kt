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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
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
import app.tanuki.assets.designSystem.TanukiSpacing
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
import app.tanuki.shared.R
import dev.zacsweers.metrox.viewmodel.metroViewModel

/** Height of the layer's own bar, matching the design system's app-bar height. */
private val SessionBarHeight = TanukiSpacing.appBarHeight

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
 * Renders whatever browser session [SiteSessionViewModel] is showing, as a full-screen layer at the app root.
 *
 * Hosted at the app root rather than being a navigation destination, because the caller is a Ktor plugin
 * suspended mid-request with no relationship to the back stack — it can be a browse fetch, a cover load,
 * or a page prefetch three screens deep. A route would need every one of those to know how to navigate and
 * how to get back; a layer driven by the coordinator's state needs none of that.
 *
 * While mounted it attaches the ViewModel to the coordinator, which is how network code tells "the user
 * declined" apart from "no UI existed to ask" — a background library update must fail fast rather than park
 * on a screen nobody is looking at.
 *
 * ### Silent first
 *
 * When to reveal a challenge is the ViewModel's call (see [SiteSessionViewModel]). Until then the layer is
 * composed — full-size, laid out, and painting, which is all the engine needs to work through a challenge —
 * but z-ordered *underneath* the app, so the user goes on reading while it solves.
 *
 * What makes this safe is that **occlusion is not the same as being headless**. The deleted offscreen
 * solvers failed because their engine was zero-size and never attached, so it never rasterized and
 * Turnstile never ran (see `decisions.md`, 2026-07-30). Being covered by something drawn in front happens
 * in the platform compositor and is invisible to the page. Hence the rules this composable keeps: while
 * silent the layer is full-size and fully opaque, and *only* its draw order changes. Never fade it, never
 * shrink it, never take it out of the composition to hide it.
 */
@Composable
fun SiteSessionHost(viewModel: SiteSessionViewModel = metroViewModel()) {
    DisposableEffect(viewModel) {
        viewModel.attach()
        onDispose { viewModel.detach() }
    }

    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // The session being rendered. Held separately from `state` because the exit animation still needs a
    // session to draw after the ViewModel has gone idle.
    var shown by remember { mutableStateOf<SiteSessionState.Active?>(null) }
    (state as? SiteSessionState.Active)?.let { shown = it }

    val session = shown ?: return
    val live = state is SiteSessionState.Active

    val reduceMotion = rememberReducedMotion()
    val enterMillis = if (reduceMotion) TanukiMotion.reducedMillis else TanukiMotion.screenEnterMillis
    val exitMillis = if (reduceMotion) TanukiMotion.reducedMillis else TanukiMotion.screenExitMillis

    // How present the layer is *visually*: 0 while it solves silently, 1 once revealed, animated between —
    // the same lift-and-fade every screen transition uses, since it still arrives unannounced in the middle
    // of whatever the user was doing. Deliberately **not** an `AnimatedVisibility`: that would keep the
    // layer out of the composition until it was revealed, and an engine that isn't composed can't solve
    // anything. This drives a `graphicsLayer` that is only applied once revealed (see the layer).
    val onScreen = live && session.revealed
    val presence by animateFloatAsState(
        targetValue = if (onScreen) 1f else 0f,
        animationSpec =
            tween(
                durationMillis = if (onScreen) enterMillis else exitMillis,
                easing = if (onScreen) TanukiMotion.EaseEnter else TanukiMotion.EaseExit,
            ),
        label = "siteSessionPresence",
    )

    // Composed while a session is live, and kept for as long as an exit animation is still running. A
    // session dismissed while still silent was never visible, so `presence` is already 0 and it simply goes.
    if (live || presence > 0f) {
        SiteSessionLayer(
            session = session,
            presence = presence,
            reduceMotion = reduceMotion,
            onSnapshot = viewModel::onSnapshot,
            onDismiss = viewModel::onDismiss,
            onDone = viewModel::onDone,
        )
    }
}

/**
 * @param presence how far through the reveal (or exit) animation the layer is, 0..1. Applied **only** when
 *   revealed — a silent layer must stay fully opaque, since an engine that isn't rasterized doesn't solve.
 */
@Composable
private fun SiteSessionLayer(
    session: SiteSessionState.Active,
    presence: Float,
    reduceMotion: Boolean,
    onSnapshot: (SiteSessionResult) -> Unit,
    onDismiss: () -> Unit,
    onDone: () -> Unit,
) {
    val revealed = session.revealed
    val isChallenge = session.isChallenge

    // Back dismisses the session rather than navigating the screen underneath, which would leave the user
    // looking at a layer they can't reach the app behind. Only while revealed: a silent layer is not what
    // the back gesture is aimed at, and swallowing it would break the app the user is still using.
    if (revealed) {
        val backState = rememberNavigationEventState(NavigationEventInfo.None)
        NavigationBackHandler(state = backState, onBackCompleted = onDismiss)
    }

    val titleResource =
        if (isChallenge) R.string.site_session_challenge_title else R.string.site_session_login_title
    val messageResource =
        if (isChallenge) R.string.site_session_challenge_message else R.string.site_session_login_message
    val dismissResource = if (isChallenge) R.string.site_session_close else R.string.site_session_cancel
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
                    onClick = onDismiss,
                    modifier = Modifier.semantics { contentDescription = dismissLabel }
                ) { tint -> CloseIcon(tint = tint, size = 22.dp) }
                Text(
                    text = stringResource(titleResource),
                    style = TanukiTheme.typography.headline,
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
                    text = stringResource(messageResource, session.host),
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
                url = session.url,
                // Expire the cookie we're waiting on before loading, so the browser must genuinely re-solve.
                // Without this Cloudflare accepts its own stale clearance in the browser, issues no new one,
                // and the session waits forever for a change that never comes.
                clearCookie = session.clearCookie,
                // While silent the engine is under the app; a tap that reached it would land on a browser
                // the user can't see. Revealing hands the touches back.
                interactive = revealed,
                onSnapshot = onSnapshot,
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
                    targetState = session.covered,
                    animationSpec = tween(TanukiMotion.screenEnterMillis, easing = TanukiMotion.EaseEnter)
                ) { covered ->
                    if (covered) ConnectingCover(host = session.host)
                }
            }
        }

        // A footer exists only where there is something to confirm. A login has no cookie that reliably
        // means "signed in", so the user says when it's done; a challenge resolves itself and gets no
        // action bar at all, handing that height to the challenge widget. Dismissing is the bar's X in
        // both cases — repeating it down here would be two controls doing one job.
        if (session.needsConfirm) {
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
                    text = stringResource(R.string.site_session_done),
                    onClick = onDone,
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
            text = stringResource(R.string.site_session_connecting, host),
            style = TanukiTheme.typography.caption,
            color = TanukiTheme.colors.textFaint,
            textAlign = TextAlign.Center
        )
    }
}