import Observation
import Shared

/// A SwiftUI screen's handle on its Kotlin ViewModel: the one piece of glue every screen shares, so a
/// screen's own Swift is just its `View`.
///
/// It owns a `ScreenScope` (the ViewModel's lifetime), mirrors the ViewModel's `uiState` into `state`
/// for SwiftUI to observe, and exposes `vm` for forwarding actions (`model.vm.onSourceClick(sourceId:)`).
/// There's no presentation logic here or in the screen. If Swift would have to compute it, it belongs
/// in the ViewModel.
///
/// ```swift
/// @State private var model = ScreenModel({ $0.homeViewModel() }, uiState: \.uiState)
/// var body: some View {
///     content(model.state)
///         .task { await model.observe() }
///         .navEvents(model.vm.navEvents, path: $path)
/// }
/// ```
///
/// - `.task` ends when the view leaves, which unsubscribes from `uiState`. The ViewModel's
///   `WhileSubscribed(5s)` then stops the pipeline, exactly as `collectAsStateWithLifecycle` does on
///   Android.
/// - `deinit` closes the scope, which clears the ViewModel when the screen is popped.
///
/// The ViewModel is created **lazily**, on first use. SwiftUI re-evaluates a `@State` initializer every
/// time the view struct is re-created and throws the extra instances away. Lazy creation means a
/// discarded instance only ever allocated an empty Kotlin `ViewModelStore`, never a ViewModel.
@MainActor
@Observable
final class ScreenModel<VM: AnyObject, S> {
    @ObservationIgnored private let scope = ScreenScope()
    @ObservationIgnored private let make: (ScreenScope) -> VM
    @ObservationIgnored private let uiState: (VM) -> SkieSwiftStateFlow<S>
    @ObservationIgnored private var created: VM?
    /// The last state `observe()` received. Tracked, so a change re-renders the screen.
    private var latest: S?

    /// - Parameters:
    ///   - make: Resolves the screen's ViewModel from its scope, e.g. `{ $0.homeViewModel() }`.
    ///   - uiState: The ViewModel's state flow, e.g. `\.uiState`.
    init(_ make: @escaping (ScreenScope) -> VM, uiState: @escaping (VM) -> SkieSwiftStateFlow<S>) {
        self.make = make
        self.uiState = uiState
    }

    /// The Kotlin ViewModel, for forwarding user actions to its methods.
    var vm: VM {
        if let created { return created }
        let viewModel = make(scope)
        created = viewModel
        return viewModel
    }

    /// The screen's current state: the last emission, or the flow's current value before the first.
    var state: S { latest ?? uiState(vm).value }

    /// Mirrors `uiState` into `state` until the calling task is cancelled. Call from the screen's `.task`.
    func observe() async {
        for await state in uiState(vm) {
            latest = state
        }
    }

    deinit {
        scope.close()
    }
}
