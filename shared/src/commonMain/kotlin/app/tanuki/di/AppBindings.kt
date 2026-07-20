package app.tanuki.di

import androidx.lifecycle.ViewModel
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.MetroViewModelFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.reflect.KClass

/**
 * Common `@Provides` bindings contributed into [AppScope] on every platform.
 *
 * Prefer constructor injection (`@Inject` / `@ContributesBinding`) for your own
 * types; use `@Provides` here only for types you cannot annotate directly.
 */
@BindingContainer
@ContributesTo(AppScope::class)
object AppBindings {
    /** Factory that resolves androidx ViewModels from the graph's multibindings. */
    @Provides
    @SingleIn(AppScope::class)
    fun provideMetroViewModelFactory(
        viewModelProviders: Map<KClass<out ViewModel>, () -> ViewModel>,
        manualAssistedFactoryProviders:
            Map<KClass<out ManualViewModelAssistedFactory>, () -> ManualViewModelAssistedFactory>
    ): MetroViewModelFactory =
        object : MetroViewModelFactory() {
            override val viewModelProviders get() = viewModelProviders
            override val manualAssistedFactoryProviders get() = manualAssistedFactoryProviders
        }

    @Provides
    @SingleIn(AppScope::class)
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}