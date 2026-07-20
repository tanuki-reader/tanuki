package app.tanuki

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import app.tanuki.assets.designSystem.TanukiTheme
import app.tanuki.di.AppGraph
import app.tanuki.navigation.TanukiNavHost
import app.tanuki.platform.SystemBarsEffect
import dev.zacsweers.metrox.viewmodel.LocalMetroViewModelFactory
import kotlinx.coroutines.launch

/** Composition local giving screens access to the app graph. */
val LocalAppGraph: ProvidableCompositionLocal<AppGraph> =
    staticCompositionLocalOf { error("No AppGraph provided") }

@Composable
fun App(appGraph: AppGraph) {
    // Dev-only: fill an empty library with sample manga so covers render before real sources exist.
    // No-op once the library has any entry; runs on the app scope so it isn't cancelled by recomposition.
    LaunchedEffect(appGraph) {
        appGraph.applicationScope.launch { appGraph.librarySeeder.seedIfEmpty() }
    }

    val darkTheme = isSystemInDarkTheme()
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