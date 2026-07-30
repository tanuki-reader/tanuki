package app.tanuki.core.source.di

import android.content.Context
import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.source.SourceBundleStore
import app.tanuki.core.source.store.FileSystemSourceBundleStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Android platform source bindings: the `.wasm` bundle store, under `<filesDir>/sources`, resolved from
 * the application [Context] contributed by the app's platform bindings (same source as the database).
 *
 * The Cloudflare solver used to be bound here too, per platform. It isn't any more — solving a managed
 * challenge needs a browser the user can *see*, so it is one common implementation driven by the UI
 * (`InteractiveCloudflareSolver`), and the only platform-specific part left is the web view composable
 * in `:shared`.
 */
@BindingContainer
@ContributesTo(AppScope::class)
object PlatformSourceBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun provideSourceBundleStore(
        context: Context,
        dispatcherProvider: DispatcherProvider,
    ): SourceBundleStore = FileSystemSourceBundleStore(
        fileSystem = FileSystem.SYSTEM,
        directory = context.applicationContext.filesDir.resolve("sources").absolutePath.toPath(),
        dispatchers = dispatcherProvider,
    )
}