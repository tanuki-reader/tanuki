package app.tanuki.core.data.repository.bookmark

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.tanuki.core.data.repository.chapter.ChapterEntity
import app.tanuki.core.data.repository.manga.MangaEntity
import app.tanuki.core.data.repository.manga.decodeHeaders
import app.tanuki.core.data.repository.manga.encodeHeaders
import app.tanuki.core.data.repository.manga.mangaCover
import app.tanuki.core.domain.bookmark.model.PageBookmark
import app.tanuki.core.domain.bookmark.model.PageBookmarkEntry
import app.tanuki.core.domain.chapter.model.ChapterNumber
import app.tanuki.core.domain.chapter.model.PageImage
import app.tanuki.core.domain.common.model.Text
import app.tanuki.core.domain.common.model.Url
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Room row for a [PageBookmark]: one per page of a chapter (unique `(chapter_id, page)`). [mangaId] is
 * denormalized so a series' bookmarks are read without joining through its chapters. Both FKs cascade:
 * a bookmark has no meaning without its chapter.
 *
 * The page's image is flattened into three columns rather than a converted type (see `Converters`):
 * [imageUrl] + [imageHeaders] for a source page, [imagePath] for a downloaded one, all `null` when the
 * reader had nothing to record.
 */
@Entity(
    tableName = "page_bookmark",
    foreignKeys = [
        ForeignKey(
            entity = ChapterEntity::class,
            parentColumns = ["id"],
            childColumns = ["chapter_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = MangaEntity::class,
            parentColumns = ["id"],
            childColumns = ["manga_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["chapter_id", "page"], unique = true),
        Index("manga_id"),
    ],
)
data class PageBookmarkEntity(
    @PrimaryKey val id: Uuid,
    @ColumnInfo(name = "manga_id") val mangaId: Uuid,
    @ColumnInfo(name = "chapter_id") val chapterId: Uuid,
    val page: Int,
    @ColumnInfo(name = "created_at") val createdAt: Instant,
    @ColumnInfo(name = "image_url") val imageUrl: String?,
    @ColumnInfo(name = "image_headers") val imageHeaders: String?,
    @ColumnInfo(name = "image_path") val imagePath: String?,
)

fun PageBookmarkEntity.toDomain(): PageBookmark =
    PageBookmark(
        id = id,
        mangaId = mangaId,
        chapterId = chapterId,
        page = page,
        createdAt = createdAt,
        image =
            when {
                imagePath != null -> PageImage.Local(imagePath)
                imageUrl != null -> PageImage.Remote(Url(imageUrl), decodeHeaders(imageHeaders))
                else -> null
            },
    )

fun PageBookmark.toEntity(): PageBookmarkEntity =
    PageBookmarkEntity(
        id = id,
        mangaId = mangaId,
        chapterId = chapterId,
        page = page,
        createdAt = createdAt,
        imageUrl = (image as? PageImage.Remote)?.url?.value,
        imageHeaders = (image as? PageImage.Remote)?.headers?.let(::encodeHeaders),
        imagePath = (image as? PageImage.Local)?.path,
    )

/** A bookmark joined to its series and chapter — what [PageBookmarkDao.observeAll] returns. */
data class PageBookmarkRow(
    @Embedded val bookmark: PageBookmarkEntity,
    @ColumnInfo(name = "manga_title") val mangaTitle: String,
    @ColumnInfo(name = "cover_url") val coverUrl: String?,
    @ColumnInfo(name = "cover_headers") val coverHeaders: String?,
    @ColumnInfo(name = "cover_override") val coverOverride: String?,
    @ColumnInfo(name = "chapter_number") val chapterNumber: Double?,
    @ColumnInfo(name = "chapter_name") val chapterName: String?,
)

fun PageBookmarkRow.toDomain(): PageBookmarkEntry =
    PageBookmarkEntry(
        bookmark = bookmark.toDomain(),
        mangaTitle = Text(mangaTitle),
        cover = mangaCover(bookmark.mangaId, coverUrl, coverHeaders, coverOverride),
        chapterNumber = ChapterNumber.orNull(chapterNumber),
        chapterName = chapterName?.let(::Text),
    )
