package app.tanuki.core.data.repository.history

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.tanuki.core.data.repository.chapter.ChapterEntity
import app.tanuki.core.data.repository.chapter.ChapterWithProgress
import app.tanuki.core.data.repository.chapter.toDomain
import app.tanuki.core.data.repository.manga.MangaEntity
import app.tanuki.core.data.repository.manga.MangaWithDetails
import app.tanuki.core.data.repository.manga.toDomain
import app.tanuki.core.domain.history.model.ChapterHistory
import app.tanuki.core.domain.history.model.HistoryEntry
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Room row for a [ChapterHistory] entry. One row per chapter (unique `chapter_id`), so re-reading a
 * chapter refreshes `read_at` in place rather than inserting a duplicate. [mangaId] is denormalized
 * so history can be queried/cleared per manga without joining through the chapter. Both FKs cascade.
 */
@Entity(
    tableName = "history",
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
        Index(value = ["chapter_id"], unique = true),
        Index("manga_id"),
        Index("read_at"),
    ],
)
data class HistoryEntity(
    @PrimaryKey val id: Uuid,
    @ColumnInfo(name = "chapter_id") val chapterId: Uuid,
    @ColumnInfo(name = "manga_id") val mangaId: Uuid,
    @ColumnInfo(name = "read_at") val readAt: Instant,
)

fun HistoryEntity.toDomain(): ChapterHistory =
    ChapterHistory(
        id = id,
        chapterId = chapterId,
        mangaId = mangaId,
        readAt = readAt,
    )

/**
 * A history row already joined to the series it belongs to and the chapter it points at — the shape
 * [HistoryDao.observeLibraryHistory] returns, and the only projection that maps straight to a domain
 * [HistoryEntry] without further id resolution.
 *
 * Both embeds carry a prefix because [MangaEntity] and [ChapterEntity] collide on `id` and `url`, and
 * an unprefixed chapter `id` would in turn collide with this row's own [chapterId]. [chapterId] is kept
 * alongside [chapter] deliberately: it is the raw `history.chapter_id`, so the entry stays identifiable
 * (and deletable) even in the outer join's null case.
 */
data class LibraryHistoryEntry(
    val id: Uuid,
    @ColumnInfo(name = "chapter_id") val chapterId: Uuid,
    @ColumnInfo(name = "read_at") val readAt: Instant,
    @Embedded(prefix = "manga_") val manga: MangaWithDetails,
    @Embedded(prefix = "ch_") val chapter: ChapterWithProgress?,
)

fun LibraryHistoryEntry.toDomain(): HistoryEntry =
    HistoryEntry(
        id = id,
        chapterId = chapterId,
        manga = manga.toDomain(),
        chapter = chapter?.toDomain(),
        readAt = readAt,
    )
