package app.tanuki.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph

@DependencyGraph(AppScope::class)
interface IosAppGraph : AppGraph {
    @DependencyGraph.Factory
    interface Factory {
        fun create(): IosAppGraph
    }
}
