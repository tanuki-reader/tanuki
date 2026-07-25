package app.tanuki.core.data.repository.settings.di

import app.tanuki.core.data.repository.settings.PreferencesFileFactory
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/** iOS preferences file-factory binding. The factory resolves its own location, so it takes no args. */
@BindingContainer
@ContributesTo(AppScope::class)
object PlatformPreferencesBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun providePreferencesFileFactory(): PreferencesFileFactory = PreferencesFileFactory()
}