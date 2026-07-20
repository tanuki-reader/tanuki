package app.tanuki.core.data.repository.history

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.tanuki.core.data.repository.chapter.ChapterEntity
import app.tanuki.core.data.repository.manga.MangaEntity
import app.tanuki.core.domain.history.model.ChapterHistory
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
