package app.tanuki.di

import app.tanuki.update.TanukiWorkerFactory
import dev.zacsweers.metrox.android.MetroAppComponentProviders

/**
 * Android-specific graph surface. Extending [MetroAppComponentProviders] lets
 * metrox-android construct Android components (e.g. Activities) from the graph.
 * The concrete `@DependencyGraph` lives in the androidApp module.
 */
interface BaseAndroidAppGraph :
    AppGraph,
    MetroAppComponentProviders {
    /**
     * Builds the app's injected [androidx.work.ListenableWorker]s. Exposed here because WorkManager is
     * configured from `Application`, which is the one place that can't ask the graph for a dependency
     * the normal way — it *is* what holds the graph.
     *
     * Typed as the app's own [TanukiWorkerFactory] rather than `WorkerFactory`, so the Metro binding
     * is on a type this project owns (a third-party type crossing a module boundary as a binding is
     * how the graph broke before).
     */
    val workerFactory: TanukiWorkerFactory
}
