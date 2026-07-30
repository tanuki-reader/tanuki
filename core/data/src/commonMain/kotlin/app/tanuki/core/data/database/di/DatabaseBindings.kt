package app.tanuki.core.data.database.di

import app.tanuki.core.data.database.DatabaseBuilderFactory
import app.tanuki.core.data.database.TanukiDatabase
import app.tanuki.core.data.database.buildDatabase
import app.tanuki.core.data.repository.category.CategoryDao
import app.tanuki.core.data.repository.chapter.ChapterDao
import app.tanuki.core.data.repository.history.HistoryDao
import app.tanuki.core.data.repository.manga.MangaDao
import app.tanuki.core.data.repository.source.SourceDao
import app.tanuki.core.data.repository.update.ChapterUpdateDao
import app.tanuki.core.domain.common.service.DispatcherProvider
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/**
 * Platform-agnostic database bindings contributed into [dev.zacsweers.metro.AppScope].
 *
 * The [app.tanuki.core.data.database.TanukiDatabase] is a singleton (a single open connection for the app's lifetime); DAOs are
 * inexpensive views onto it. The platform-specific [app.tanuki.core.data.database.DatabaseBuilderFactory] is provided separately in each
 * target's binding container.
 */
@BindingContainer
@ContributesTo(AppScope::class)
object DatabaseBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun provideDatabase(
        factory: DatabaseBuilderFactory,
        dispatcherProvider: DispatcherProvider
    ): TanukiDatabase = factory.create().buildDatabase(dispatcherProvider)

    @Provides
    fun provideSourceDao(database: TanukiDatabase): SourceDao = database.sourceDao()

    @Provides
    fun provideMangaDao(database: TanukiDatabase): MangaDao = database.mangaDao()

    @Provides
    fun provideChapterDao(database: TanukiDatabase): ChapterDao = database.chapterDao()

    @Provides
    fun provideChapterUpdateDao(database: TanukiDatabase): ChapterUpdateDao = database.chapterUpdateDao()

    @Provides
    fun provideHistoryDao(database: TanukiDatabase): HistoryDao = database.historyDao()

    @Provides
    fun provideCategoryDao(database: TanukiDatabase): CategoryDao = database.categoryDao()
}