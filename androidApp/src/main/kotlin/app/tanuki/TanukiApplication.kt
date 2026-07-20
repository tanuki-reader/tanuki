package app.tanuki

import android.app.Application
import app.tanuki.di.AndroidAppGraph
import dev.zacsweers.metro.createGraphFactory
import dev.zacsweers.metrox.android.MetroAppComponentProviders
import dev.zacsweers.metrox.android.MetroApplication

class TanukiApplication : Application(), MetroApplication {

    val appGraph: AndroidAppGraph by lazy {
        createGraphFactory<AndroidAppGraph.Factory>().create(application = this)
    }

    // Lets metrox-android construct graph-injected components (e.g. MainActivity).
    override val appComponentProviders: MetroAppComponentProviders
        get() = appGraph
}