package app.tanuki

import app.tanuki.core.domain.settings.model.ThemePreference
import app.tanuki.di.IosAppGraph
import dev.zacsweers.metro.createGraphFactory
import kotlinx.coroutines.flow.Flow

/**
 * The app graph, `internal` rather than private because the UI is not its only entry point: iOS can
 * launch the app straight into a background refresh, with no view controller ever created, and the
 * background task handler needs the same single graph the UI would have used.
 */
internal val appGraph: IosAppGraph by lazy {
    createGraphFactory<IosAppGraph.Factory>().create()
}

/**
 * Starts the app's launch jobs ([AppStartup]). Called from the scene's `.task` in `iOSApp.swift` rather
 * than `App.init`, because `init` also runs when iOS launches the app into a background task.
 */
fun startApp() = appGraph.appStartup.start()

/** The user's theme choice, for the SwiftUI root's `.preferredColorScheme`. */
fun observeThemePreference(): Flow<ThemePreference> = appGraph.observeThemePreference()
