package app.tanuki.core.data.database

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.TypeConverters
import app.tanuki.core.data.repository.category.CategoryDao
import app.tanuki.core.data.repository.category.CategoryEntity
import app.tanuki.core.data.repository.category.MangaCategoryEntity
import app.tanuki.core.data.repository.chapter.ChapterDao
import app.tanuki.core.data.repository.chapter.ChapterEntity
import app.tanuki.core.data.repository.chapter.ReadingProgressEntity
import app.tanuki.core.data.repository.download.ChapterDownloadDao
import app.tanuki.core.data.repository.download.ChapterDownloadEntity
import app.tanuki.core.data.repository.history.HistoryDao
import app.tanuki.core.data.repository.history.HistoryEntity
import app.tanuki.core.data.repository.library.LibraryEntryEntity
import app.tanuki.core.data.repository.manga.MangaDao
import app.tanuki.core.data.repository.manga.MangaEntity
import app.tanuki.core.data.repository.source.SourceDao
import app.tanuki.core.data.repository.source.SourceEntity
import app.tanuki.core.data.repository.update.ChapterUpdateDao
import app.tanuki.core.data.repository.update.ChapterUpdateEntity

/**
 * The single Room database for the app, shared across Android and iOS.
 *
 * Entities and DAOs are registered here; each feature adds its own. The concrete implementation is
 * generated per platform by Room's KSP processor and materialised through [TanukiDatabaseConstructor]
 * (see [ConstructedBy]). Build instances via the platform [DatabaseBuilderFactory], never directly.
 */
@Database(
    entities = [
        SourceEntity::class,
        MangaEntity::class,
        LibraryEntryEntity::class,
        ChapterEntity::class,
        ReadingProgressEntity::class,
        ChapterUpdateEntity::class,
        ChapterDownloadEntity::class,
        HistoryEntity::class,
        CategoryEntity::class,
        MangaCategoryEntity::class
    ],
    version = 11,
    exportSchema = true
)
@TypeConverters(Converters::class)
@ConstructedBy(TanukiDatabaseConstructor::class)
abstract class TanukiDatabase : RoomDatabase() {
    abstract fun sourceDao(): SourceDao

    abstract fun mangaDao(): MangaDao

    abstract fun chapterDao(): ChapterDao

    abstract fun chapterUpdateDao(): ChapterUpdateDao

    abstract fun chapterDownloadDao(): ChapterDownloadDao

    abstract fun historyDao(): HistoryDao

    abstract fun categoryDao(): CategoryDao
}

/**
 * Room-generated factory that constructs the platform [TanukiDatabase] implementation.
 *
 * The `actual` is emitted by Room's KSP processor for each target, so no handwritten `actual` exists
 * — hence the suppression.
 */
@Suppress("KotlinNoActualForExpect", "NO_ACTUAL_FOR_EXPECT")
expect object TanukiDatabaseConstructor : RoomDatabaseConstructor<TanukiDatabase> {
    override fun initialize(): TanukiDatabase
}