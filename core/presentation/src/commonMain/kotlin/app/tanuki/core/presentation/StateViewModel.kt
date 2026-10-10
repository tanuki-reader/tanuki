package app.tanuki.core.presentation

import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.common.service.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * A [TanukiViewModel] whose screen state is *derived* — one [StateFlow] projected from domain flows and
 * screen-local knobs, shared under [asState] so it is collected only while the screen is watching.
 *
 * This is the second tier [TanukiViewModel] predicted rather than a type parameter on that class: the
 * ViewModels [asState] does not fit (the reader, source browse) keep their own `MutableStateFlow` on the
 * base and are not forced through a shape that doesn't suit them. Note the reader's state *is* derived —
 * what excludes it is this class's dispatcher, ordering and lifetime contract, not its state shape; see
 * [TanukiViewModel] for the three reasons in full.
 *
 * **What extending this buys beyond the two lines it saves:** [uiState] has no setter and no backing
 * field a subclass can see, so `uiState.value = …` stops compiling. The pattern this class exists to
 * replace — a pipeline pushed into a mutable field from `launchIn(viewModelScope)`, which runs for the
 * ViewModel's whole lifetime whether or not any UI is subscribed — becomes unrepresentable rather than
 * merely discouraged. A ViewModel still declaring its own state field is therefore saying something.
 *
 * **Why the sharing strategy is not a parameter.** Every subclass today is contributed to `AppScope`, so
 * every one of them is exactly the always-on case this class was written for: a pipeline that would
 * otherwise hold a Room subscription tree for the life of the process, including while the user is deep
 * in another screen. A `SharingStarted` knob would let a caller opt back out of the only guarantee the
 * class makes, so a screen that needs a different lifetime wants the base class, not a parameter here.
 *
 * @param initialState What [uiState] reads before the pipeline has produced anything, and while it is
 *   stopped with nothing yet emitted — normally the screen's `Loading`.
 * @param S The screen's state. Bounded to [Any] for Swift: an unbounded `S` is nullable, so SKIE would
 *   hand SwiftUI `uiState` as a flow of *optional* states — a phantom `nil` case in every `switch`.
 */
abstract class StateViewModel<S : Any>(
    dispatcher: DispatcherProvider,
    logger: Logger,
    private val initialState: S
) : TanukiViewModel(dispatcher, logger) {
    /**
     * The screen's pipeline. Assembled by the subclass, collected only while [uiState] has a subscriber.
     *
     * Do not add `flowOn` here — [asState] already maps on [DispatcherProvider.default].
     */
    protected abstract val state: Flow<S>

    /**
     * The screen state. Shared, lifecycle-scoped and stopped shortly after the last collector leaves;
     * the last value is retained, so a tab round trip resumes on what was on screen rather than
     * flashing [initialState] again.
     *
     * `by lazy` is load-bearing: [state] is an abstract property, so it is not initialized while this
     * constructor runs and cannot be read from here. Deferring to first access is also when the
     * pipeline *should* be built — nothing is assembled for a screen nobody has asked for.
     */
    val uiState: StateFlow<S> by lazy { state.asState(initialState) }
}
