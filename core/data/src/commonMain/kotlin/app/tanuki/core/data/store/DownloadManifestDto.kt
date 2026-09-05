package app.tanuki.core.data.store

import app.tanuki.core.domain.download.model.DownloadManifest
import app.tanuki.core.domain.download.model.DownloadedPage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The on-disk shape of a `manifest.json`.
 *
 * Separate from the domain [DownloadManifest] for the same reason entities are separate from models:
 * this one is a **file format**. It is written today and read back by a build shipped months later, so
 * its field names and types are a compatibility promise, and it must not move whenever the domain model
 * is refactored.
 *
 * Everything is a primitive on purpose — [Uuid] as its canonical string, the timestamp as epoch millis
 * — so the format never depends on which serializers a given kotlinx.serialization version happens to
 * ship for Kotlin's own value types, and stays trivially readable by anything else that opens the file.
 *
 * @see DownloadManifest.CURRENT_VERSION for when to bump [version].
 */
@Serializable
internal data class DownloadManifestDto(
    val version: Int,
    @SerialName("chapter_id") val chapterId: String,
    val pages: List<DownloadedPageDto>,
    @SerialName("total_bytes") val totalBytes: Long,
    @SerialName("downloaded_at") val downloadedAt: Long,
)

/** One page inside a [DownloadManifestDto]. */
@Serializable
internal data class DownloadedPageDto(
    val index: Int,
    val file: String,
    val bytes: Long,
)

internal fun DownloadManifest.toDto(): DownloadManifestDto =
    DownloadManifestDto(
        version = version,
        chapterId = chapterId.toString(),
        pages = pages.map { DownloadedPageDto(index = it.index, file = it.file, bytes = it.bytes) },
        totalBytes = totalBytes,
        downloadedAt = downloadedAt.toEpochMilliseconds(),
    )

internal fun DownloadManifestDto.toDomain(): DownloadManifest =
    DownloadManifest(
        version = version,
        chapterId = Uuid.parse(chapterId),
        pages = pages.map { DownloadedPage(index = it.index, file = it.file, bytes = it.bytes) },
        totalBytes = totalBytes,
        downloadedAt = Instant.fromEpochMilliseconds(downloadedAt),
    )
