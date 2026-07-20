package app.tanuki

import androidx.compose.ui.window.ComposeUIViewController
import app.tanuki.di.IosAppGraph
import dev.zacsweers.metro.createGraphFactory

private val appGraph: IosAppGraph by lazy {
    createGraphFactory<IosAppGraph.Factory>().create()
}

fun MainViewController() = ComposeUIViewController { App(appGraph) }
