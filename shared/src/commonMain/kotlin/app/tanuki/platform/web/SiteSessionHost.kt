package app.tanuki.platform.web

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tanuki.assets.designSystem.TanukiSpacing
import app.tanuki.core.source.net.session.PendingSiteSession
import app.tanuki.core.source.net.session.SiteSessionCompletion
import app.tanuki.core.source.net.session.SiteSessionCoordinator
import app.tanuki.core.source.net.session.SiteSessionReason
import app.tanuki.core.source.net.session.SiteSessionResult
import app.tanuki.core.source.net.session.isSatisfiedBy
import app.tanuki.shared.resources.Res
import app.tanuki.shared.resources.site_session_cancel
import app.tanuki.shared.resources.site_session_challenge_message
import app.tanuki.shared.resources.site_session_challenge_title
import app.tanuki.shared.resources.site_session_close
import app.tanuki.shared.resources.site_session_done
import app.tanuki.shared.resources.site_session_login_message
import app.tanuki.shared.resources.site_session_login_title
import org.jetbrains.compose.resources.stringResource

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
    pending?.let { session -> SiteSessionLayer(session) }
}

@OptIn(ExperimentalComposeUiApi::class) // BackHandler
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
    BackHandler(enabled = true) { session.cancel() }

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
        val isChallenge = request.reason == SiteSessionReason.CLOUDFLARE_CHALLENGE
        val titleResource =
            if (isChallenge) Res.string.site_session_challenge_title else Res.string.site_session_login_title
        val messageResource =
            if (isChallenge) Res.string.site_session_challenge_message else Res.string.site_session_login_message
        val dismissResource = if (isChallenge) Res.string.site_session_close else Res.string.site_session_cancel

        Column(
            modifier = Modifier.fillMaxWidth().padding(TanukiSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(TanukiSpacing.xs)
        ) {
            Text(text = stringResource(titleResource), style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(messageResource, request.host),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
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

            // Cover the browser whenever it is *not* showing a challenge. The engine has to keep
            // rendering to solve one, so the view stays composed and laid out underneath — this only
            // hides it. Without it the user watches the site load, and on an API host that means a
            // screenful of raw JSON in the moment between the challenge clearing and the layer closing.
            if (!snapshot.onChallengePage) {
                Box(
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = TanukiSpacing.sm),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { session.cancel() }) {
                Text(text = stringResource(dismissResource), textAlign = TextAlign.Center)
            }
            // A login has no cookie that reliably means "signed in", so the user says when it's done.
            if (request.completion == SiteSessionCompletion.WhenUserConfirms) {
                TextButton(onClick = { session.finish(snapshot) }) {
                    Text(text = stringResource(Res.string.site_session_done))
                }
            }
        }
    }
}