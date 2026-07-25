package app.tanuki.core.data.repository.settings.di

import android.content.Context
import app.tanuki.core.data.repository.settings.PreferencesFileFactory
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/**
 * Android preferences file-factory binding. Depends on the application [Context] contributed by the
 * app's platform bindings (same source the database builder uses).
 */
@BindingContainer
@ContributesTo(AppScope::class)
object PlatformPreferencesBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun providePreferencesFileFactory(context: Context): PreferencesFileFactory =
        PreferencesFileFactory(context)
}