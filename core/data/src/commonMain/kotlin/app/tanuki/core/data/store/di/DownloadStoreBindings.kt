package app.tanuki.core.data.store.di

import app.tanuki.core.data.store.DownloadDirectoryFactory
import app.tanuki.core.data.store.FileSystemChapterDownloadStore
import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.download.service.ChapterDownloadStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM

/**
 * Binds the downloaded-chapter store. Platform-agnostic: only the root path differs per platform, and
 * that arrives as the [DownloadDirectoryFactory] each target's binding container provides.
 *
 * `okio`'s [FileSystem] and [okio.Path] stay inside this module — the domain port deals in ids, bytes
 * and plain strings — which is what lets the store be tested against `FakeFileSystem` without any
 * platform involved.
 */
@BindingContainer
@ContributesTo(AppScope::class)
object DownloadStoreBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun provideChapterDownloadStore(
        factory: DownloadDirectoryFactory,
        dispatcherProvider: DispatcherProvider,
    ): ChapterDownloadStore =
        FileSystemChapterDownloadStore(
            fileSystem = FileSystem.SYSTEM,
            root = factory.resolve().toPath(),
            dispatchers = dispatcherProvider,
        )
}
