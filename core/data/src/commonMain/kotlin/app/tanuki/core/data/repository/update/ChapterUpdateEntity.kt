package app.tanuki.core.data.repository.update

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.tanuki.core.data.repository.chapter.ChapterEntity
import app.tanuki.core.domain.update.model.ChapterUpdate
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Room row for a [ChapterUpdate] — the local-only note that a chapter arrived after the user was
 * already following its series.
 *
 * A side table on `chapters` for the same reason `reading_progress` is one: the chapter row is
 * source-owned and rewritten on every sync, so anything the app decides about a chapter has to live
 * beside it or be clobbered. [chapterId] is both the primary key and a cascade FK, which makes the
 * record 1:1 with its chapter and lets a pruned chapter (or a deleted manga, which cascades through
 * `chapters`) take its update with it — no cleanup pass needed.
 *
 * `manga_id` is denormalized rather than reached through the chapter: grouping updates by series and
 * clearing them when a series leaves the library are both frequent, and neither should need a join.
 * `discovered_at` is indexed because every read of this table is newest-first.
 */
@Entity(
    tableName = "chapter_updates",
    foreignKeys = [
        ForeignKey(
            entity = ChapterEntity::class,
            parentColumns = ["id"],
            childColumns = ["chapter_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("manga_id"), Index("discovered_at")],
)
data class ChapterUpdateEntity(
    @PrimaryKey @ColumnInfo(name = "chapter_id") val chapterId: Uuid,
    @ColumnInfo(name = "manga_id") val mangaId: Uuid,
    @ColumnInfo(name = "discovered_at") val discoveredAt: Instant,
    val notified: Boolean,
)

fun ChapterUpdateEntity.toDomain(): ChapterUpdate =
    ChapterUpdate(
        chapterId = chapterId,
        mangaId = mangaId,
        discoveredAt = discoveredAt,
        notified = notified,
    )

fun ChapterUpdate.toEntity(): ChapterUpdateEntity =
    ChapterUpdateEntity(
        chapterId = chapterId,
        mangaId = mangaId,
        discoveredAt = discoveredAt,
        notified = notified,
    )