package app.tanuki

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tanuki.assets.designSystem.TanukiTheme
import app.tanuki.core.domain.settings.model.ThemePreference
import app.tanuki.di.AppGraph
import app.tanuki.navigation.TanukiNavHost
import app.tanuki.platform.SystemBarsEffect
import app.tanuki.platform.imaging.newTanukiImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import dev.zacsweers.metrox.viewmodel.LocalMetroViewModelFactory
import kotlinx.coroutines.launch

/** Composition local giving screens access to the app graph. */
val LocalAppGraph: ProvidableCompositionLocal<AppGraph> =
    staticCompositionLocalOf { error("No AppGraph provided") }

@Composable
fun App(appGraph: AppGraph) {
    // One image loader for the whole app, both platforms — memory + disk cache and Ktor fetching are
    // configured in newTanukiImageLoader (covers are immutable, so both tiers are sized to keep them).
    setSingletonImageLoaderFactory(::newTanukiImageLoader)

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
    CompositionLocalProvider(
        LocalAppGraph provides appGraph,
        LocalMetroViewModelFactory provides appGraph.metroViewModelFactory
    ) {
        // Keep the status/navigation bar glyphs legible against the theme (the UI is edge-to-edge).
        SystemBarsEffect(darkTheme)
        TanukiTheme(darkTheme = darkTheme) {
            TanukiNavHost()
        }
    }
}