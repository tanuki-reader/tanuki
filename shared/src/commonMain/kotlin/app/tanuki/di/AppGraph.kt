package app.tanuki.di

import app.tanuki.core.domain.common.service.CrashReporter
import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.domain.download.service.DownloadScheduler
import app.tanuki.core.domain.download.usecase.RunDownloadQueue
import app.tanuki.core.domain.download.usecase.VerifyDownloads
import app.tanuki.core.domain.settings.usecase.ObserveCrashReportingEnabled
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
     * The crash reporter, and the stored opt-in that gates it. Both are exposed on the graph rather than
     * behind a screen's ViewModel because the subscription tying them together belongs to the app's
     * lifetime: consent has to be applied at launch, before any screen exists, and revoking it has to
     * take effect wherever the user happens to be.
     */
    val crashReporter: CrashReporter

    /** The stored crash-reporting opt-in. See [crashReporter]. */
    val observeCrashReportingEnabled: ObserveCrashReportingEnabled

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

    /**
     * Reconciles the download records against the files, at launch, before the queue is picked up.
     * Exposed for the same reason as [downloadScheduler] — it runs once per process with no screen
     * behind it, and it has to run *first*, so that what the engine then works is a queue that matches
     * what is actually on the device.
     */
    val verifyDownloads: VerifyDownloads

    /**
     * Gets the download queue moving. Exposed on the graph because the launch-time resume in [App] has
     * no screen behind it: a queue the app was killed in the middle of has to be picked up before the
     * user opens anything, and every other caller reaches it through a use case.
     */
    val downloadScheduler: DownloadScheduler

    /**
     * Works the queue and reports on it. Exposed for the same reason as [updateLibrary]: iOS's
     * `BGProcessingTask` handler is a static launch handler with no injected collaborators, so the one
     * thing it needs has to be reachable from the graph itself.
     */
    val runDownloadQueue: RunDownloadQueue

    /** Temporary dev helper: seed sample manga into an empty library. Remove once sources exist. */
    val librarySeeder: LibrarySeeder

    /**
     * Temporary dev helper: installs the app-bundled `.wasm` sources into the bundle store. Remove
     * once the remote source repo and its installer exist.
     */
    val devSourceInstaller: DevSourceInstaller
}