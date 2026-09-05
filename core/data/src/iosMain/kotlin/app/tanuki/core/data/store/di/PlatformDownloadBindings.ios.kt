package app.tanuki.core.data.store.di

import app.tanuki.core.data.store.DownloadDirectoryFactory
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/** iOS downloads-root binding. The factory resolves its own location, so it takes no args. */
@BindingContainer
@ContributesTo(AppScope::class)
object PlatformDownloadBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun provideDownloadDirectoryFactory(): DownloadDirectoryFactory = DownloadDirectoryFactory()
}
