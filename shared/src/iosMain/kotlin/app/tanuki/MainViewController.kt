package app.tanuki

import androidx.compose.ui.window.ComposeUIViewController
import app.tanuki.di.IosAppGraph
import dev.zacsweers.metro.createGraphFactory

/**
 * The app graph, `internal` rather than private because the UI is not its only entry point: iOS can
 * launch the app straight into a background refresh, with no view controller ever created, and the
 * background task handler needs the same single graph the UI would have used.
 */
internal val appGraph: IosAppGraph by lazy {
    createGraphFactory<IosAppGraph.Factory>().create()
}

fun MainViewController() = ComposeUIViewController { App(appGraph) }
