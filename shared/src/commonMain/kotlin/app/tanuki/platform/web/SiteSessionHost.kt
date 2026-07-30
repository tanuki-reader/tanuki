package app.tanuki.platform.web

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import app.tanuki.core.source.net.session.SiteSessionCompletion
import app.tanuki.core.source.net.session.SiteSessionCoordinator
import app.tanuki.core.source.net.session.SiteSessionReason
import app.tanuki.core.source.net.session.SiteSessionResult
import app.tanuki.core.source.net.session.isSatisfiedBy
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
import org.jetbrains.compose.resources.stringResource

/** Height of the layer's own bar, matching the design system's app-bar height. */
private val SessionBarHeight = 56.dp

/** The reason badge's a tinted plate — big enough to read as a plate, small enough to sit on a text line. */
private val ReasonBadgeSize = 32.dp

/** How far the layer travels on enter/exit, as a fraction of its height — a lift, not a full sheet slide. */
private const val SLIDE_FRACTION = 24

/**
 * Renders whatever browser session [coordinator] is asking for, as a full-screen layer above the rest of
 * the app.
 *
 * Hosted at the app root rather than being a navigation destination, because the caller is a Ktor plugin
 * suspended mid-request with no relationship to the back stack — it can be a browse fetch, a cover load,
 * or a page prefetch three screens deep. A route would need every one of those to know how to navigate and
 * how to get back; a layer driven by the coordinator's state needs none of that.
 *
 * While mounted it registers with the coordinator, which is how network code tells "the user declined"
 * apart from "no UI existed to ask" — a background library update must fail fast rather than park on a
 * screen nobody is looking at.
 */
@Composable
fun SiteSessionHost(coordinator: SiteSessionCoordinator) {
    DisposableEffect(coordinator) {
        coordinator.attach()
        onDispose { coordinator.detach() }
    }

    val pending by coordinator.pending.collectAsStateWithLifecycle()

    // The layer arrives unannounced, in the middle of whatever the user was doing, so it rises into place
    // rather than cutting in — the same enter/exit motion budget every screen transition uses. Held
    // separately from `pending` because the exit animation still needs a session to render while the
    // coordinator has already cleared it.
    var leaving by remember { mutableStateOf<PendingSiteSession?>(null) }
    pending?.let { leaving = it }

    val reduceMotion = rememberReducedMotion()
    val enterMillis = if (reduceMotion) TanukiMotion.reducedMillis else TanukiMotion.screenEnterMillis
    val exitMillis = if (reduceMotion) TanukiMotion.reducedMillis else TanukiMotion.screenExitMillis

    AnimatedVisibility(
        visible = pending != null,
        enter =
            if (reduceMotion) {
                fadeIn(tween(enterMillis))
            } else {
                fadeIn(tween(enterMillis, easing = TanukiMotion.EaseEnter)) +
                    slideInVertically(tween(enterMillis, easing = TanukiMotion.EaseEnter)) { it / SLIDE_FRACTION }
            },
        exit =
            if (reduceMotion) {
                fadeOut(tween(exitMillis))
            } else {
                fadeOut(tween(exitMillis, easing = TanukiMotion.EaseExit)) +
                    slideOutVertically(tween(exitMillis, easing = TanukiMotion.EaseExit)) { it / SLIDE_FRACTION }
            }
    ) {
        leaving?.let { session -> SiteSessionLayer(session) }
    }
}

@Composable
private fun SiteSessionLayer(session: PendingSiteSession) {
    val request = session.request
    // The latest snapshot the browser reported. Held so the confirm action has something to hand back for
    // a login, where no predicate can decide on the user's behalf.
    var snapshot by remember(session) { mutableStateOf(SiteSessionResult(emptyMap(), null)) }
    // The cookies the browser already had before this session loaded anything. Platform cookie stores are
    // shared and persistent, so a stale cf_clearance is usually sitting there — see isSatisfiedBy.
    val baseline = remember(session) { mutableStateOf<Map<String, String>?>(null) }

    // Back dismisses the session rather than navigating the screen underneath, which would leave the user
    // looking at a layer they can't reach the app behind.
    val backState = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(state = backState, onBackCompleted = { session.cancel() })

    val isChallenge = request.reason == SiteSessionReason.CLOUDFLARE_CHALLENGE
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
                .background(MaterialTheme.colorScheme.background)
                // Swallow every gesture that isn't the web view's. This is a layer over a live NavHost, so
                // without it a tap on the header would fall through and drive the screen behind.
                .pointerInput(session) {
                    awaitPointerEventScope { while (true) awaitPointerEvent() }
                }.safeDrawingPadding()
    ) {
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

        // The site's own surface, hairline-separated from the app's chrome above and below so it is
        // visually obvious where Tanuki stops and the site begins. Full-bleed rather than inset in a card:
        // a Turnstile widget (and a login form under a keyboard) wants every pixel of height it can get.
        Box(
            modifier = Modifier.fillMaxWidth().weight(1f).background(MaterialTheme.colorScheme.surface)
        ) {
            SiteSessionWebView(
                url = request.url,
                // Expire the cookie we're waiting on before loading, so the browser must genuinely re-solve.
                // Without this Cloudflare accepts its own stale clearance in the browser, issues no new one,
                // and the session waits forever for a change that never comes.
                clearCookie = (request.completion as? SiteSessionCompletion.WhenCookieSet)?.cookieName,
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
            Crossfade(
                targetState = isChallenge && !snapshot.onChallengePage,
                animationSpec = tween(TanukiMotion.screenEnterMillis, easing = TanukiMotion.EaseEnter)
            ) { covered ->
                if (covered) ConnectingCover(host = request.host)
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