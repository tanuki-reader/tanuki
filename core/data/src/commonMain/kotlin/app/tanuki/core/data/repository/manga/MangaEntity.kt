package app.tanuki.core.data.repository.manga

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.tanuki.core.data.repository.library.LibraryEntryEntity
import app.tanuki.core.data.repository.source.SourceEntity
import app.tanuki.core.domain.common.model.Text
import app.tanuki.core.domain.common.model.Url
import app.tanuki.core.domain.common.model.toTextOrNull
import app.tanuki.core.domain.manga.model.Manga
import app.tanuki.core.domain.manga.model.MangaCover
import app.tanuki.core.domain.manga.model.PublishStatus
import app.tanuki.core.domain.manga.model.ReadingMode
import app.tanuki.core.domain.manga.model.ReadingStatus
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Room row for a [Manga] — source-owned metadata only. The user's library state lives in the separate
 * [LibraryEntryEntity]; a manga can exist here (cached from a browse/read) without being in the
 * library. The domain [MangaCover] is flattened onto the row and rebuilt at the mapper boundary.
 *
 * `source` (Uuid) foreign-keys to [SourceEntity.id] and cascades, so deleting a source removes its
 * manga. A manga is naturally identified within its source by its source-native [sourceMangaKey] —
 * hence the unique `(source, source_manga_key)` index (whose leftmost `source` also serves the FK's
 * child-column index). [url] is a display/permalink, not the identity: url-less sources fall back to
 * the source home, so many series would share one url and cannot dedupe on it.
 */
@Entity(
    tableName = "manga",
    foreignKeys = [
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["source"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["source", "source_manga_key"], unique = true)],
)
data class MangaEntity(
    @PrimaryKey val id: Uuid,
    val source: Uuid,
    val title: String,
    val author: String?,
    val description: String?,
    @ColumnInfo(name = "cover_url") val coverUrl: String?,
    @ColumnInfo(name = "cover_last_modified") val coverLastModified: Instant,
    @ColumnInfo(name = "source_manga_key") val sourceMangaKey: String?,
    val url: String,
    @ColumnInfo(name = "reading_mode") val readingMode: ReadingMode,
    @ColumnInfo(name = "publish_status") val publishStatus: PublishStatus,
)

/**
 * A [MangaEntity] plus the columns the read queries derive via LEFT JOINs / subqueries: `last_read`
 * (latest `history.read_at`, `null` when never read), the `library` row's `date_added` /
 * `reading_status` (`null` when the manga is not in the library), and `unread_count` (chapters with
 * no progress or `already_read = 0`). Library membership is exactly `dateAdded != null`.
 */
data class MangaWithDetails(
    @Embedded val manga: MangaEntity,
    @ColumnInfo(name = "last_read") val lastRead: Instant?,
    @ColumnInfo(name = "date_added") val dateAdded: Instant?,
    @ColumnInfo(name = "reading_status") val readingStatus: ReadingStatus?,
    @ColumnInfo(name = "unread_count") val unreadCount: Long,
)

fun MangaWithDetails.toDomain(): Manga =
    manga.toDomain(lastRead, dateAdded, readingStatus, unreadCount.toUInt())

fun MangaEntity.toDomain(
    lastRead: Instant?,
    dateAdded: Instant?,
    readingStatus: ReadingStatus?,
    unreadCount: UInt,
): Manga =
    Manga(
        id = id,
        source = source,
        title = Text(title),
        author = author?.let(::Text),
        description = description?.let(::Text),
        cover = MangaCover(mangaId = id, url = coverUrl?.let(::Url), lastModified = coverLastModified),
        sourceMangaKey = sourceMangaKey.toTextOrNull(),
        url = Url(url),
        // Downloads aren't tracked in the DB yet (no download subsystem — see LibraryFilter), so this
        // stays at its default until that lands. `unread_count` is derived by the read queries.
        unreadCount = unreadCount,
        dateAdded = dateAdded,
        lastRead = lastRead,
        readingMode = readingMode,
        publishStatus = publishStatus,
        readingStatus = readingStatus,
    )

fun Manga.toEntity(): MangaEntity =
    MangaEntity(
        id = id,
        source = source,
        title = title.value,
        author = author?.value,
        description = description?.value,
        coverUrl = cover.url?.value,
        coverLastModified = cover.lastModified,
        sourceMangaKey = sourceMangaKey?.value,
        url = url.value,
        readingMode = readingMode,
        publishStatus = publishStatus,
    )

/**
 * The library row to persist for this manga, or `null` when it is not in the library (which the
 * repository treats as "remove from library"). Membership is exactly having a [Manga.dateAdded], which
 * adding to the library is what supplies.
 */
fun Manga.toLibraryEntry(): LibraryEntryEntity? =
    dateAdded?.let { addedAt ->
        LibraryEntryEntity(
            id = id,
            dateAdded = addedAt,
            readingStatus = readingStatus ?: ReadingStatus.PLANNING_TO_READ,
        )
    }