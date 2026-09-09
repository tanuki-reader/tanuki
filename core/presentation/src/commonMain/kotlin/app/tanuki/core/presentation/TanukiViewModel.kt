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
 *    [io]/[tryIo] for the "log what failed, let cancellation through" pattern the writing paths repeat.
 *    Note the *hop* in those two is a boundary guarantee rather than the thing keeping the main thread
 *    free — see [io] for which layer actually owns it.
 *
 * What it deliberately does *not* own is `uiState` — not because no ViewModel should share one, but
 * because they do not all share the *same* one. A type parameter on this class would impose a screen
 * state on the three settings ViewModels that expose a single domain value under its own name (`theme`,
 * `categories`, …), and on [app.tanuki.core.presentation.StateViewModel]'s own generic signature.
 *
 * So the shape is a **second tier**: [StateViewModel] extends this and owns a `uiState` derived through
 * [asState], which is what every screen whose state is a projection of domain flows should extend. Two
 * screens extend *this* class instead and declare their own `MutableStateFlow` through Kotlin's explicit
 * backing field, for two different reasons:
 *
 *  - **Source browse** genuinely has no derived state: a cursor-driven paging machine, eight imperative
 *    writes, nothing to project from.
 *  - **The reader's state *is* derived** — every one of its three states comes out of one pipeline, and
 *    that pipeline's `onEach` is the only writer of its `uiState`. What doesn't fit is [asState] itself,
 *    in three ways. It maps on [DispatcherProvider.default], and the reader's re-anchor bookkeeping is
 *    mutated from both the pipeline and the main thread, so the hop would make those an unsynchronised
 *    pair. Its scroll commands must be computed *after* the state is published, and a subclass's `state`
 *    flow sits upstream of `stateIn`, where nothing has published yet. And `WhileSubscribed` would stop
 *    and reseed a stateful `scan` it depends on, for a route-scoped screen that is visible for its whole
 *    life and so saves nothing by stopping.
 *
 * So the marker a declared state field carries is: *either* this screen's state isn't derived, *or*
 * [asState]'s dispatcher/ordering/lifetime contract doesn't fit it. Not the first alone, and never
 * "reached for by default".
 *
 * The same reasoning keeps screen-specific one-shot effects local (e.g. `ReaderViewModel.commands`).
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
     * Runs [block] on [DispatcherProvider.io].
     *
     * **This is a boundary guarantee, not what keeps the main thread free** — every layer a ViewModel
     * calls into already dispatches for itself: Room, through the `setQueryCoroutineContext(io)` its
     * builder applies (`DatabaseBuilder.buildDatabase`, so "suspending DAO calls never block the caller's
     * thread"); DataStore, through its own scope; `FileSystemChapterDownloadStore` and `WasmSourceLoader`,
     * through explicit `withContext`. Kept anyway because a `withContext` to the context you are already
     * in does not dispatch, so it costs nothing, and because nothing guarantees the next callee will.
     *
     * The corollary, for the layer below: **a repository wraps in `withContext(io)` only for work it does
     * itself** — mapping a row list to domain, batching, encoding — never for a plain DAO delegation,
     * which Room has already moved off the caller's thread. Compare `MangaRepositoryImp.getMangaByIds`
     * (`readInBatches` + `toDomainList()` over hundreds of rows, so it wraps) with `setReadingMode` (a
     * one-line delegation, so it doesn't).
     */
    protected suspend fun <T> io(block: suspend CoroutineScope.() -> T): T = withContext(dispatcher.io, block)

    /**
     * [io] for a call that is allowed to fail: logs the failure against [tag] with [what] as the message
     * and hands back the [Result] so the caller decides what the screen shows.
     *
     * The dispatch is the lesser half (see [io]); what this is really for is the pair of rules around it —
     * one place that logs what failed, and cancellation re-thrown rather than reported, so a load dropped
     * by a closing screen or a superseded search unwinds silently instead of painting an error over state
     * that has already moved on. A bare `runCatching` around a suspend call gets the second one wrong; use
     * [runCatchingCancellable] where the hop isn't wanted.
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