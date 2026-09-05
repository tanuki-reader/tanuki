package app.tanuki.core.data.store.di

import android.content.Context
import app.tanuki.core.data.store.DownloadDirectoryFactory
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/**
 * Android downloads-root binding. Depends on the application [Context] contributed by the app's
 * platform bindings — the same source the database and preferences bindings use.
 */
@BindingContainer
@ContributesTo(AppScope::class)
object PlatformDownloadBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun provideDownloadDirectoryFactory(context: Context): DownloadDirectoryFactory =
        DownloadDirectoryFactory(context)
}
