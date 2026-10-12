package app.tanuki.core.data.database

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.TypeConverters
import app.tanuki.core.data.repository.bookmark.PageBookmarkDao
import app.tanuki.core.data.repository.bookmark.PageBookmarkEntity
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
import app.tanuki.core.data.repository.metadata.MetadataDao
import app.tanuki.core.data.repository.metadata.SeriesMetadataEntity
import app.tanuki.core.data.repository.metadata.TrackerLinkEntity
import app.tanuki.core.data.repository.source.SourceDao
import app.tanuki.core.data.repository.source.SourceEntity
import app.tanuki.core.data.repository.sourcerepo.SourceInstallationDao
import app.tanuki.core.data.repository.sourcerepo.SourceInstallationEntity
import app.tanuki.core.data.repository.sourcerepo.SourceRepoDao
import app.tanuki.core.data.repository.sourcerepo.SourceRepoEntity
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
        MangaCategoryEntity::class,
        TrackerLinkEntity::class,
        SeriesMetadataEntity::class,
        PageBookmarkEntity::class,
        SourceRepoEntity::class,
        SourceInstallationEntity::class
    ],
    version = 18,
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

    abstract fun metadataDao(): MetadataDao

    abstract fun pageBookmarkDao(): PageBookmarkDao

    abstract fun sourceRepoDao(): SourceRepoDao

    abstract fun sourceInstallationDao(): SourceInstallationDao
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