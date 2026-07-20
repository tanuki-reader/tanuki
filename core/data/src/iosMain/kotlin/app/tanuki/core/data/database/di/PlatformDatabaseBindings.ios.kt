package app.tanuki.core.data.database.di

import app.tanuki.core.data.database.DatabaseBuilderFactory
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/** iOS database builder binding. The factory resolves its own on-disk location, so it takes no args. */
@BindingContainer
@ContributesTo(AppScope::class)
object PlatformDatabaseBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun provideDatabaseBuilderFactory(): DatabaseBuilderFactory = DatabaseBuilderFactory()
}