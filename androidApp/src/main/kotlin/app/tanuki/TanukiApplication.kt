package app.tanuki

import android.app.Application
import androidx.work.Configuration
import app.tanuki.di.AndroidAppGraph
import dev.zacsweers.metro.createGraphFactory
import dev.zacsweers.metrox.android.MetroAppComponentProviders
import dev.zacsweers.metrox.android.MetroApplication

/**
 * Also WorkManager's [Configuration.Provider], which is what lets the library-update worker take
 * constructor dependencies: WorkManager instantiates workers reflectively, so the graph's factory has
 * to be handed to it from here. The manifest removes the default `WorkManagerInitializer` so this
 * configuration is the one that takes effect rather than a stock one racing it at startup.
 */
class TanukiApplication :
    Application(),
    MetroApplication,
    Configuration.Provider {

    val appGraph: AndroidAppGraph by lazy {
        createGraphFactory<AndroidAppGraph.Factory>().create(application = this)
    }

    // Lets metrox-android construct graph-injected components (e.g. MainActivity).
    override val appComponentProviders: MetroAppComponentProviders
        get() = appGraph

    override val workManagerConfiguration: Configuration
        get() =
            Configuration
                .Builder()
                .setWorkerFactory(appGraph.workerFactory)
                .build()
}