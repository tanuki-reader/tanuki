package app.tanuki.core.data.database.di

import android.content.Context
import app.tanuki.core.data.database.DatabaseBuilderFactory
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/**
 * Android database builder binding. Depends on the application [Context] contributed by the app's
 * platform bindings.
 */
@BindingContainer
@ContributesTo(AppScope::class)
object PlatformDatabaseBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun provideDatabaseBuilderFactory(context: Context): DatabaseBuilderFactory = DatabaseBuilderFactory(context)
}