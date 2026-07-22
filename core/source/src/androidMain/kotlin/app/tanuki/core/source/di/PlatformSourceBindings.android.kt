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
 * Android source-bundle store binding: `.wasm` bundles live under `<filesDir>/sources`, resolved from
 * the application [Context] contributed by the app's platform bindings (same source as the database).
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