package app.tanuki

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tanuki.assets.designSystem.TanukiTheme
import app.tanuki.core.domain.settings.model.ThemePreference
import app.tanuki.core.domain.update.usecase.UpdateLibrary
import app.tanuki.di.AppGraph
import app.tanuki.features.reader.LocalPageImageLoader
import app.tanuki.navigation.TanukiNavHost
import app.tanuki.platform.SystemBarsEffect
import app.tanuki.platform.rememberReducedMotion
import app.tanuki.platform.imaging.newTanukiImageLoader
import app.tanuki.platform.web.SiteSessionHost
import app.tanuki.platform.web.WebEngineWarmupEffect
import app.tanuki.platform.imaging.newTanukiPageImageLoader
import coil3.compose.LocalPlatformContext
import coil3.compose.setSingletonImageLoaderFactory
import dev.zacsweers.metrox.viewmodel.LocalMetroViewModelFactory
import kotlinx.coroutines.launch

/** Composition local giving screens access to the app graph. */
val LocalAppGraph: ProvidableCompositionLocal<AppGraph> =
    staticCompositionLocalOf { error("No AppGraph provided") }

@Composable
fun App(appGraph: AppGraph) {
    // The shared, Cloudflare-interceptor-carrying client both image loaders fetch over, so covers/pages
    // inherit the cf_clearance source API calls solve (see AppGraph.sourceHttpClient).
    val httpClient = appGraph.sourceHttpClient
    // One image loader for the whole app, both platforms — memory + disk cache and Ktor fetching are
    // configured in newTanukiImageLoader (covers are immutable, so both tiers are sized to keep them).
    setSingletonImageLoaderFactory { context -> newTanukiImageLoader(context, httpClient) }

//    // Dev-only: fill an empty library with sample manga so covers render before real sources exist.
//    // No-op once the library has any entry; runs on the app scope so it isn't cancelled by recomposition.
//    LaunchedEffect(appGraph) {
//        appGraph.applicationScope.launch { appGraph.librarySeeder.seedIfEmpty() }
//    }

    // Dev-only: install the app-bundled `.wasm` sources so the source pipeline has something to load.
    // Separate effect from the seeder because the two are independent — neither should block the other.
    LaunchedEffect(appGraph) {
        appGraph.applicationScope.launch { appGraph.devSourceInstaller.installBundled() }
    }

    // Check the library for new chapters if the user's interval says one is due. Runs on the app scope,
    // so leaving the screen (or the composition going away) doesn't cancel a refresh in flight.
    //
    // This foreground catch-up is not a stand-in for the platform schedulers — it is the half that
    // works even when they don't fire. iOS grants background refresh opportunistically and may skip a
    // rarely-opened app for days; without a check on launch, those users would never see an update.
    // It stays silent: the user is looking at the Home list it feeds.
    LaunchedEffect(appGraph) {
        appGraph.applicationScope.launch { appGraph.updateLibrary(UpdateLibrary.Trigger.Foreground) }
    }

    // Reconcile the downloads, then pick the queue back up — in that order, and therefore in one effect:
    // two effects would give no ordering guarantee, and the order is the whole point. The sweep clears
    // records whose files are gone and files no record claims (storage cleared from the OS, a chapter a
    // sync pruned, a part an abandoned run left), so what the engine then works is a queue that matches
    // the device. It is safe beside a live run — see VerifyDownloads for why — and a no-op on a healthy
    // one.
    //
    // Picking the queue up is the second half, and nothing else would do it: enqueueing starts a run, but
    // a queue the user left behind — the app was killed, the chapter was still fetching — has no enqueue
    // coming to wake it, and would sit there looking active while nothing fetched it. The engine requeues
    // the rows an interrupted run left mid-flight and carries on; an empty queue makes this a no-op.
    LaunchedEffect(appGraph) {
        appGraph.applicationScope.launch {
            appGraph.verifyDownloads()
            appGraph.downloadScheduler.ensureRunning()
        }
    }

    // Keep the history table bounded. Nothing else in the app ever removes a history row on age, and
    // both readers that take no period walk every row of it on each chapter the reader opens.
    //
    // Its own effect, and ungated: a DELETE matching nothing fires no Room trigger, so on every launch
    // until an install is two calendar years old this costs one indexed scan and invalidates no query.
    // A series' most recent read is always kept, whatever its age — see PruneHistory for why that is
    // load-bearing rather than polite.
    LaunchedEffect(appGraph) {
        appGraph.applicationScope.launch { appGraph.pruneHistory() }
    }

    // Hand the platform scheduler the user's interval, and re-hand it whenever they change it. Runs for
    // the app's lifetime — it's a subscription, not a one-shot.
    LaunchedEffect(appGraph) {
        appGraph.applicationScope.launch { appGraph.keepLibraryUpdateScheduled() }
    }

    // Apply the user's crash-reporting consent, and keep applying it. This is the *only* thing that ever
    // starts the reporter: the SDK installs process-wide crash handlers, so it must not run before the
    // stored preference has been read, and it must stop the moment consent is withdrawn. Collecting the
    // flow rather than reading it once is what makes the second half true — unticking the switch shuts
    // the reporter down on the same emission, with no relaunch. On the app scope, so the subscription
    // outlives any screen.
    LaunchedEffect(appGraph) {
        appGraph.applicationScope.launch {
            appGraph.observeCrashReportingEnabled().collect(appGraph.crashReporter::setEnabled)
        }
    }

    // The user's stored theme choice drives the whole UI: System defers to the OS setting, while Light
    // and Dark force a dialect. Collected at the root so a change re-themes every screen at once.
    val themePreference by appGraph
        .observeThemePreference()
        .collectAsStateWithLifecycle(ThemePreference.System)
    val darkTheme =
        when (themePreference) {
            ThemePreference.System -> isSystemInDarkTheme()
            ThemePreference.Light -> false
            ThemePreference.Dark -> true
        }
    // The reader's page loader — one process-lived instance, built here and handed down to the reader.
    // Separate from the singleton cover loader set above (see newTanukiPageImageLoader for why).
    val platformContext = LocalPlatformContext.current
    val pageImageLoader =
        remember(platformContext, httpClient) { newTanukiPageImageLoader(platformContext, httpClient) }

    CompositionLocalProvider(
        LocalAppGraph provides appGraph,
        LocalMetroViewModelFactory provides appGraph.metroViewModelFactory,
        LocalPageImageLoader provides pageImageLoader
    ) {
        // Keep the status/navigation bar glyphs legible against the theme (the UI is edge-to-edge).
        SystemBarsEffect(darkTheme)
        // Start the browser engine early, so the first Cloudflare challenge doesn't pay for it mid-scroll.
        WebEngineWarmupEffect()
        // Read once, here, and pushed into the theme: the design system's self-animating components
        // (the progress ring's indeterminate sweep) have no other way to see the OS setting, since the
        // platform call lives in this module and the design system sits below it.
        TanukiTheme(darkTheme = darkTheme, reducedMotion = rememberReducedMotion()) {
            // An explicit stacking parent, because the session layer moves *through* the nav host rather
            // than simply sitting on top of it: a Cloudflare challenge starts underneath the app and solves
            // there unseen, and only comes over the top if it turns out to need the user (see
            // SiteSessionHost). That z-order flip needs a parent that stacks its children.
            Box(modifier = Modifier.fillMaxSize()) {
                TanukiNavHost()
                // Not inside the nav host: a challenge or a login prompt can be raised by any request on
                // any screen, so it is a layer over the whole app rather than a destination.
                SiteSessionHost(appGraph.siteSessionCoordinator)
            }
        }
    }
}