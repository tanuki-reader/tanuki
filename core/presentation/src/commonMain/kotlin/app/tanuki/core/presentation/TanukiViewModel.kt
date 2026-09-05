package app.tanuki.core.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.domain.common.service.warn
import app.tanuki.core.navigation.Destination
import app.tanuki.core.navigation.NavEvent
import app.tanuki.core.navigation.NavEvent.NavBack
import app.tanuki.core.navigation.NavEvent.NavTo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Base class for every screen's ViewModel. Compose-free (per CLAUDE.md → MVVM), so it lives in `:core` —
 * a horizontal capability every feature depends on — rather than in any one feature.
 *
 * It owns the parts every screen repeats rather than the parts each screen decides:
 *
 *  - **Screen-scoped navigation.** [navEvents] plus [navigateTo]/[navigateBack]. Screen-local pushes only;
 *    cross-tab navigation and deep links go through [app.tanuki.core.navigation.AppNavigator] instead.
 *  - **The two services every ViewModel needs**, [dispatcher] and [logger], as constructor parameters
 *    subclasses forward — Metro resolves them from the graph the same way it did per-ViewModel before.
 *  - **The idioms built on them:** [asState] for a screen state derived straight from a domain flow, and
 *    [io]/[tryIo] for the "hop to IO, log what failed" pattern the writing paths repeat.
 *
 * What it deliberately does *not* own is `uiState`, even though six of the ViewModels declare an
 * identical-looking one. Hoisting it into a `TanukiViewModel<S>` costs more than the two lines it saves:
 *
 *  - A subclass could no longer write to it. Those six use Kotlin's explicit backing field
 *    (`val uiState: StateFlow<S> field = MutableStateFlow(...)`), which is visible only inside the
 *    *declaring* class — so a generic base would have to expose a separate `protected` mutable property,
 *    reinstating the `_uiState`/`uiState` pair that syntax exists to remove.
 *  - It fits six of the eleven. `SettingsViewModel`/`SourcesViewModel` derive their state through
 *    [asState] and have no mutable field to hoist, and the three other settings ViewModels expose a
 *    single domain value under its own name (`theme`, `categories`, …) rather than a screen state.
 *
 * If that ever changes, the shape to reach for is a second tier — a `StateViewModel<S>` extending this —
 * not a type parameter on this class. The same reasoning keeps screen-specific one-shot effects local
 * (e.g. `ReaderViewModel.commands`).
 *
 * @property dispatcher Where work is scheduled; see [io].
 * @property logger Where failures go; see [tag] for what they are tagged with.
 */
abstract class TanukiViewModel(
    protected val dispatcher: DispatcherProvider,
    protected val logger: Logger
) : ViewModel() {
    /**
     * The tag every log from this ViewModel carries. Defaults to the concrete class's simple name, so a
     * subclass gets a correct tag without declaring one; override to log under a different name.
     */
    protected open val tag: String
        get() = this::class.simpleName ?: "TanukiViewModel"

    private val navEventChannel = Channel<NavEvent>(Channel.BUFFERED)

    /**
     * Screen-scoped navigation intents. One-shot and buffered so an intent emitted before the host starts
     * collecting — a tap during the first composition — isn't lost; the host maps these onto the active
     * back stack. A [Channel] rather than a `StateFlow` so re-subscribing can't replay a stale push.
     */
    val navEvents: Flow<NavEvent> = navEventChannel.receiveAsFlow()

    /** Request a push to [destination] within the current tab's back stack. */
    protected fun navigateTo(destination: Destination) {
        navEventChannel.trySend(NavTo(destination))
    }

    /** Request a pop of the current destination. */
    protected fun navigateBack() {
        navEventChannel.trySend(NavBack)
    }

    /**
     * Projects a domain flow into screen state: maps on [DispatcherProvider.default] and shares one
     * collection across subscribers, keeping it alive briefly past the last 'unsubscribe' so a
     * configuration change or a tab round trip resumes rather than re-runs the upstream.
     */
    protected fun <T> Flow<T>.asState(initialValue: T): StateFlow<T> =
        flowOn(dispatcher.default)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = initialValue
            )

    /**
     * Runs [block] on [DispatcherProvider.io]. For the repositories' suspend writes, which — unlike their
     * Flows — don't dispatch for themselves.
     */
    protected suspend fun <T> io(block: suspend CoroutineScope.() -> T): T = withContext(dispatcher.io, block)

    /**
     * [io] for a call that is allowed to fail: logs the failure against [tag] with [what] as the message
     * and hands back the [Result] so the caller decides what the screen shows.
     *
     * Cancellation is re-thrown rather than reported — see [runCatchingCancellable].
     */
    protected suspend fun <T> tryIo(
        what: String,
        block: suspend CoroutineScope.() -> T
    ): Result<T> =
        runCatchingCancellable { withContext(dispatcher.io, block) }
            .onFailure { logger.warn(tag, what, it) }

    private companion object {
        /**
         * How long [asState] keeps the upstream alive after the last subscriber leaves — long enough to
         * cover a configuration change or a tab switch, short enough not to hold a subscription open.
         */
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/**
 * [runCatching] that lets a [CancellationException] through instead of turning it into a `Failure`.
 *
 * Plain `runCatching` catches everything, cancellation included — so a load cancelled by a tab switch or
 * a superseded search would fall into the `onFailure` branch and paint an error over state that has
 * already moved on. Re-throwing keeps cancellation doing what it's supposed to: unwind silently.
 */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    runCatching(block).onFailure { if (it is CancellationException) throw it }