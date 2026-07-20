package app.tanuki.di

import dev.zacsweers.metrox.android.MetroAppComponentProviders

/**
 * Android-specific graph surface. Extending [MetroAppComponentProviders] lets
 * metrox-android construct Android components (e.g. Activities) from the graph.
 * The concrete `@DependencyGraph` lives in the androidApp module.
 */
interface BaseAndroidAppGraph : AppGraph, MetroAppComponentProviders
