package app.tanuki.core.data.repository.download

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.tanuki.core.data.repository.chapter.ChapterEntity
import app.tanuki.core.domain.download.model.ChapterDownload
import app.tanuki.core.domain.download.model.DownloadError
import app.tanuki.core.domain.download.model.DownloadState
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Room row for a [ChapterDownload] — the app's index of what is queued for, or kept on, the device.
 *
 * A side table on `chapters`, for the same reason `chapter_updates` and `reading_progress` are: the
 * chapter row is source-owned and rewritten on every sync, so anything the app decides about a chapter
 * has to live beside it or be clobbered. [chapterId] is both the primary key and a cascade FK, which
 * makes the record 1:1 with its chapter and lets a pruned chapter (or a deleted manga, cascading
 * through `chapters`) take its record with it.
 *
 * `manga_id` is denormalized rather than reached through the chapter: per-series counts and per-series
 * deletes are both frequent, and — more to the point — the on-disk directory is `<mangaId>/<chapterId>`,
 * so every file operation needs the series id and none of them should need a join to get it.
 *
 * The three indexes each pay for a query the UI runs constantly: `manga_id` for a chapter list's
 * per-row states, `state` for the downloaded counts behind the library badge, and `queued_at` for the
 * queue's order.
 *
 * The cascade takes rows and **never files** — see
 * [app.tanuki.core.domain.download.service.ChapterDownloadStore] for the disk side and why the two are
 * reconciled rather than assumed to agree.
 */
@Entity(
    tableName = "chapter_downloads",
    foreignKeys = [
        ForeignKey(
            entity = ChapterEntity::class,
            parentColumns = ["id"],
            childColumns = ["chapter_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("manga_id"), Index("state"), Index("queued_at")],
)
data class ChapterDownloadEntity(
    @PrimaryKey @ColumnInfo(name = "chapter_id") val chapterId: Uuid,
    @ColumnInfo(name = "manga_id") val mangaId: Uuid,
    val state: DownloadState,
    @ColumnInfo(name = "page_count") val pageCount: Int?,
    @ColumnInfo(name = "downloaded_pages") val downloadedPages: Int,
    val bytes: Long,
    val error: DownloadError?,
    @ColumnInfo(name = "queued_at") val queuedAt: Instant,
    @ColumnInfo(name = "completed_at") val completedAt: Instant?,
)

fun ChapterDownloadEntity.toDomain(): ChapterDownload =
    ChapterDownload(
        chapterId = chapterId,
        mangaId = mangaId,
        state = state,
        queuedAt = queuedAt,
        downloadedPages = downloadedPages,
        pageCount = pageCount,
        bytes = bytes,
        error = error,
        completedAt = completedAt,
    )

fun ChapterDownload.toEntity(): ChapterDownloadEntity =
    ChapterDownloadEntity(
        chapterId = chapterId,
        mangaId = mangaId,
        state = state,
        pageCount = pageCount,
        downloadedPages = downloadedPages,
        bytes = bytes,
        error = error,
        queuedAt = queuedAt,
        completedAt = completedAt,
    )
