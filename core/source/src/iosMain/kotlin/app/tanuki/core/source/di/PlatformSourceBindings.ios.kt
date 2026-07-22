package app.tanuki.core.source.di

import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.source.SourceBundleStore
import app.tanuki.core.source.store.FileSystemSourceBundleStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlinx.cinterop.ExperimentalForeignApi
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

/**
 * iOS source-bundle store binding: `.wasm` bundles live under `<Application Support>/sources`, an
 * app-internal location that persists across launches. The store resolves its own path, so it takes
 * no platform args beyond the dispatcher.
 */
@BindingContainer
@ContributesTo(AppScope::class)
object PlatformSourceBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun provideSourceBundleStore(
        dispatcherProvider: DispatcherProvider,
    ): SourceBundleStore = FileSystemSourceBundleStore(
        fileSystem = FileSystem.SYSTEM,
        directory = "${applicationSupportDirectory()}/sources".toPath(),
        dispatchers = dispatcherProvider,
    )

    @OptIn(ExperimentalForeignApi::class)
    private fun applicationSupportDirectory(): String {
        val url = NSFileManager.defaultManager.URLForDirectory(
            directory = NSApplicationSupportDirectory,
            inDomain = NSUserDomainMask,
            appropriateForURL = null,
            create = true,
            error = null,
        )
        return requireNotNull(url?.path) { "Could not resolve iOS Application Support directory" }
    }
}