package app.tanuki.di

import app.tanuki.core.domain.settings.usecase.ObserveThemePreference
import app.tanuki.dev.DevSourceInstaller
import app.tanuki.dev.LibrarySeeder
import dev.zacsweers.metrox.viewmodel.ViewModelGraph
import kotlinx.coroutines.CoroutineScope

/**
 * Platform-agnostic surface of the app's dependency graph.
 *
 * Concrete graphs (`AndroidAppGraph`, `IosAppGraph`) implement this and are
 * created via `createGraphFactory<…>()`. Extending [ViewModelGraph] wires up the
 * metrox multibindings that back `metroViewModel()` in Compose.
 */
interface AppGraph : ViewModelGraph {
    /** Application-lifetime coroutine scope. */
    val applicationScope: CoroutineScope

    /**
     * The user's theme choice, observed at the UI root ([App]) to pick the light/dark scheme. Exposed on
     * the graph (rather than resolved via a ViewModel) because it drives the theme wrapping the whole
     * navigation graph, above any single screen's ViewModel.
     */
    val observeThemePreference: ObserveThemePreference

    /** Temporary dev helper: seed sample manga into an empty library. Remove once sources exist. */
    val librarySeeder: LibrarySeeder

    /**
     * Temporary dev helper: installs the app-bundled `.wasm` sources into the bundle store. Remove
     * once the remote source repo and its installer exist.
     */
    val devSourceInstaller: DevSourceInstaller
}