package app.tanuki.core.data.repository.chapter

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.tanuki.core.data.repository.manga.MangaEntity
import app.tanuki.core.domain.chapter.model.Chapter
import app.tanuki.core.domain.chapter.model.ChapterNumber
import app.tanuki.core.domain.chapter.model.ReadingProgress
import app.tanuki.core.domain.common.model.Text
import app.tanuki.core.domain.common.model.Url
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Room row for a [Chapter] as fetched from a source. [id] is our surrogate key; [url] is the source's
 * natural key, unique within a manga (see the `chapter_manga_url` index) and used to match a remote
 * chapter to its local row on refresh. FKs to `manga(id)` with cascade so chapters follow their manga.
 *
 * `chapter_number` is a nullable `REAL` with no adapter: it is mapped through [ChapterNumber.orNull]
 * at the boundary so legacy sentinels collapse to the domain's "unknown == null".
 */
@Entity(
    tableName = "chapters",
    foreignKeys = [
        ForeignKey(
            entity = MangaEntity::class,
            parentColumns = ["id"],
            childColumns = ["manga_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["manga_id", "url"], unique = true, name = "chapter_manga_url"),
        Index("manga_id"),
    ],
)
data class ChapterEntity(
    @PrimaryKey val id: Uuid,
    @ColumnInfo(name = "manga_id") val mangaId: Uuid,
    @ColumnInfo(name = "chapter_number") val chapterNumber: Double?,
    val name: String,
    val url: String,
    @ColumnInfo(name = "date_upload") val dateUpload: Instant?,
    @ColumnInfo(name = "date_fetch") val dateFetch: Instant,
)

/**
 * Local-only reading progress, kept in its own table so source syncs (which only touch `chapters`)
 * can never clobber it. An absent row means [Chapter.progress] == `null`.
 */
@Entity(
    tableName = "reading_progress",
    foreignKeys = [
        ForeignKey(
            entity = ChapterEntity::class,
            parentColumns = ["id"],
            childColumns = ["chapter_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ReadingProgressEntity(
    @PrimaryKey @ColumnInfo(name = "chapter_id") val chapterId: Uuid,
    @ColumnInfo(name = "has_bookmark") val hasBookmark: Boolean,
    @ColumnInfo(name = "last_page_read") val lastPageRead: Long,
    @ColumnInfo(name = "already_read") val alreadyRead: Boolean,
)

/**
 * A [ChapterEntity] LEFT JOINed with its (nullable) reading-progress columns. When no progress row
 * exists all three columns are `null`, which maps to [Chapter.progress] == `null`.
 */
data class ChapterWithProgress(
    @Embedded val chapter: ChapterEntity,
    @ColumnInfo(name = "has_bookmark") val hasBookmark: Boolean?,
    @ColumnInfo(name = "last_page_read") val lastPageRead: Long?,
    @ColumnInfo(name = "already_read") val alreadyRead: Boolean?,
)

fun ChapterWithProgress.toDomain(): Chapter =
    Chapter(
        id = chapter.id,
        mangaId = chapter.mangaId,
        chapterNumber = ChapterNumber.orNull(chapter.chapterNumber),
        name = Text(chapter.name),
        url = Url(chapter.url),
        dateUpload = chapter.dateUpload,
        dateFetch = chapter.dateFetch,
        // No progress row joined (all columns null) => the user hasn't interacted yet.
        progress =
            hasBookmark?.let {
                ReadingProgress(
                    hasBookmark = it,
                    lastPageRead = (lastPageRead ?: 0L).toUInt(),
                    alreadyRead = alreadyRead ?: false,
                )
            },
    )

fun Chapter.toEntity(): ChapterEntity =
    ChapterEntity(
        id = id,
        mangaId = mangaId,
        chapterNumber = chapterNumber?.value,
        name = name.value,
        url = url.value,
        dateUpload = dateUpload,
        dateFetch = dateFetch,
    )

fun Chapter.toProgressEntity(): ReadingProgressEntity? =
    progress?.let {
        ReadingProgressEntity(
            chapterId = id,
            hasBookmark = it.hasBookmark,
            lastPageRead = it.lastPageRead.toLong(),
            alreadyRead = it.alreadyRead,
        )
    }
