package app.tanuki.di

import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.domain.settings.usecase.ObserveThemePreference
import app.tanuki.core.domain.update.usecase.KeepLibraryUpdateScheduled
import app.tanuki.core.domain.update.usecase.UpdateLibrary
import app.tanuki.core.source.net.session.SiteSessionCoordinator
import app.tanuki.dev.DevSourceInstaller
import app.tanuki.dev.LibrarySeeder
import dev.zacsweers.metrox.viewmodel.ViewModelGraph
import io.ktor.client.HttpClient
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
     * The app's logger. Exposed for the handful of entry points that run outside any injected class —
     * notably the iOS background task handler, which the OS calls as a static launch handler.
     */
    val logger: Logger

    /**
     * The one shared, Cloudflare-interceptor-carrying HTTP client (see
     * [app.tanuki.core.source.net.platformSourceHttpClient]). Exposed so the cover/page image loaders can
     * be built on it — image fetches then inherit the same `cf_clearance` a source's API calls solved for
     * a host, and no request to a protected source bypasses the interceptor.
     */
    val sourceHttpClient: HttpClient

    /**
     * The bridge network code uses to ask the user for a browser session — a Cloudflare challenge to tap
     * through, later a source login. Exposed on the graph because its UI host lives at the app root,
     * above every screen's ViewModel: any request from anywhere can raise one.
     */
    val siteSessionCoordinator: SiteSessionCoordinator

    /**
     * The user's theme choice, observed at the UI root ([App]) to pick the light/dark scheme. Exposed on
     * the graph (rather than resolved via a ViewModel) because it drives the theme wrapping the whole
     * navigation graph, above any single screen's ViewModel.
     */
    val observeThemePreference: ObserveThemePreference

    /**
     * The library-wide chapter check, exposed on the graph (rather than behind a screen's ViewModel)
     * because its callers are outside the UI: the catch-up run at app start in [App], and — once they
     * exist — the platform background schedulers. No screen owns it.
     */
    val updateLibrary: UpdateLibrary

    /**
     * Keeps the platform's background schedule matching the user's chosen interval. Exposed for the
     * same reason as [updateLibrary]: it belongs to the app's lifetime, not to any screen.
     */
    val keepLibraryUpdateScheduled: KeepLibraryUpdateScheduled

    /** Temporary dev helper: seed sample manga into an empty library. Remove once sources exist. */
    val librarySeeder: LibrarySeeder

    /**
     * Temporary dev helper: installs the app-bundled `.wasm` sources into the bundle store. Remove
     * once the remote source repo and its installer exist.
     */
    val devSourceInstaller: DevSourceInstaller
}