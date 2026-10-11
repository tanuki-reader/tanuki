package app.tanuki

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tanuki.assets.designSystem.TanukiTheme
import app.tanuki.core.domain.settings.model.ThemePreference
import app.tanuki.di.AppGraph
import app.tanuki.features.reader.LocalPageImageLoader
import app.tanuki.navigation.TanukiNavHost
import app.tanuki.platform.SystemBarsEffect
import app.tanuki.platform.rememberReducedMotion
import app.tanuki.platform.imaging.newTanukiImageLoader
import app.tanuki.platform.web.SiteSessionHost
import app.tanuki.platform.web.WebEngineWarmupEffect
import app.tanuki.platform.imaging.sharedPageImageLoader
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.compose.LocalPlatformContext
import coil3.compose.setSingletonImageLoaderFactory
import dev.zacsweers.metrox.viewmodel.LocalMetroViewModelFactory
import io.ktor.client.HttpClient

/** Composition local giving screens access to the app graph. */
val LocalAppGraph: ProvidableCompositionLocal<AppGraph> =
    staticCompositionLocalOf { error("No AppGraph provided") }

/**
 * The Android app's root: the whole UI as one Compose tree, the [TanukiNavHost] with the browser-session
 * layer over it. iOS has a SwiftUI root instead and hosts individual Compose screens through
 * [TanukiComposeHost] (see `ComposeBridge.kt`).
 */
@Composable
fun App(appGraph: AppGraph) {
    TanukiComposeHost(appGraph) { darkTheme ->
        // Keep the status/navigation bar glyphs legible against the theme (the UI is edge-to-edge).
        SystemBarsEffect(darkTheme)
        // Start the browser engine early, so the first Cloudflare challenge doesn't pay for it mid-scroll.
        WebEngineWarmupEffect()
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

/**
 * What every Compose tree in the app runs inside, whoever hosts it: Android's single [App] tree, or one of
 * iOS's per-screen bridge controllers during the SwiftUI migration. It provides the image loaders, the
 * graph and ViewModel factory locals, and the theme, so a screen renders the same in either host.
 *
 * The launch jobs (source install, library catch-up, download resume…) are not started here: they live in
 * AppStartup, which each platform's UI entry point starts once per process.
 *
 * @param content the tree, given the resolved dark-theme flag for hosts that sync system chrome to it.
 */
@Composable
fun TanukiComposeHost(
    appGraph: AppGraph,
    content: @Composable (darkTheme: Boolean) -> Unit
) {
    // The shared, Cloudflare-interceptor-carrying client both image loaders fetch over, so covers/pages
    // inherit the cf_clearance source API calls solve (see AppGraph.sourceHttpClient).
    val httpClient = appGraph.sourceHttpClient
    // One image loader for the whole app, both platforms — memory + disk cache and Ktor fetching are
    // configured in newTanukiImageLoader (covers are immutable, so both tiers are sized to keep them).
    // Coil keeps the first factory it is given, so further hosts calling this don't build another.
    setSingletonImageLoaderFactory { context -> newTanukiImageLoader(context, httpClient) }

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
    // The reader's page loader. Separate from the singleton cover loader set above (see
    // newTanukiPageImageLoader for why), and process-wide like it: several hosts each building their own
    // would mean several loaders over the one `page_cache` disk directory.
    val pageImageLoader = sharedPageImageLoader(LocalPlatformContext.current, httpClient)

    CompositionLocalProvider(
        LocalAppGraph provides appGraph,
        LocalMetroViewModelFactory provides appGraph.metroViewModelFactory,
        LocalPageImageLoader provides pageImageLoader
    ) {
        // Read once, here, and pushed into the theme: the design system's self-animating components
        // (the progress ring's indeterminate sweep) have no other way to see the OS setting, since the
        // platform call lives in this module and the design system sits below it.
        TanukiTheme(darkTheme = darkTheme, reducedMotion = rememberReducedMotion()) {
            content(darkTheme)
        }
    }
}

