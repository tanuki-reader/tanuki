package app.tanuki.core.navigation

import app.tanuki.core.navigation.NavCommand.DeepLink
import app.tanuki.core.navigation.NavCommand.SelectTab
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * App-wide navigator for cross-cutting navigation — tab switches and deep links — that no single
 * screen owns. A process singleton (wired in `coreModule`), injected into any ViewModel that needs to
 * navigate outside its own screen. The root host — the one shared Compose `Scaffold`/`NavHost` (in
 * `:shared`), which renders identically on Android and iOS — is the single collector of [commands] and
 * performs the actual tab selection / push.
 *
 * Screen-local pushes (open a manga's detail *within* the current tab) do NOT go through here — those
 * stay on the owning ViewModel's [NavEvent] flow. See [NavCommand] for the rationale behind the split.
 */
interface AppNavigator {
    /**
     * One-shot app-level commands, collected once by the root host. Never replays a stale command, so
     * re-subscribing (config change, tab re-entry) can't re-fire the last navigation.
     */
    val commands: Flow<NavCommand>

    /** Switch to a root tab. */
    fun selectTab(tab: TopLevelDestination)

    /** Navigate to any destination from anywhere, crossing tabs if needed (deep links). */
    fun deepLink(destination: Destination)
}

/**
 * Default [AppNavigator] backed by a buffered [Channel].
 *
 * A channel (not a `StateFlow`) because these are one-shot effects: each command is delivered exactly
 * once and never replayed. [Channel.BUFFERED] so a command emitted before the host starts collecting —
 * e.g. a deep link on cold start — is held until it does, rather than dropped. [Channel.trySend] is
 * used so the (non-suspend) navigator methods can be called straight from ViewModel actions without a
 * coroutine scope; the buffer makes a failed sending effectively impossible for navigation volumes.
 */
class DefaultAppNavigator : AppNavigator {
    private val _commands = Channel<NavCommand>(Channel.BUFFERED)
    override val commands: Flow<NavCommand> = _commands.receiveAsFlow()

    override fun selectTab(tab: TopLevelDestination) {
        _commands.trySend(SelectTab(tab))
    }

    override fun deepLink(destination: Destination) {
        _commands.trySend(DeepLink(destination))
    }
}