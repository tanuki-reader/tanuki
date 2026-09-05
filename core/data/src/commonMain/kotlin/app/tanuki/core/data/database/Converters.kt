package app.tanuki.core.data.database

import androidx.room.TypeConverter
import app.tanuki.core.domain.download.model.DownloadError
import app.tanuki.core.domain.download.model.DownloadState
import app.tanuki.core.domain.manga.model.PublishStatus
import app.tanuki.core.domain.manga.model.ReadingMode
import app.tanuki.core.domain.manga.model.ReadingStatus
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Room type converters for domain types SQLite cannot store natively.
 *
 * - [Uuid] → canonical hyphenated `TEXT` (portable, human-readable).
 * - [Instant] → epoch-millis `INTEGER`.
 * - enums → their `name` as `TEXT` (stable across reorderings, unlike ordinals).
 *
 * The [app.tanuki.core.domain.common.model.Text] and [app.tanuki.core.domain.common.model.Url] value
 * classes are intentionally *not* here: each is flattened to a plain `String` column in its entity and
 * re-wrapped in the mappers, mirroring the existing category handling. (Room does not reliably accept a
 * Kotlin value class as a converted column type, so flattening is the deliberate pattern.)
 */
class Converters {
    @TypeConverter
    fun uuidToString(uuid: Uuid): String = uuid.toString()

    @TypeConverter
    fun stringToUuid(value: String): Uuid = Uuid.parse(value)

    @TypeConverter
    fun instantToEpochMillis(instant: Instant): Long = instant.toEpochMilliseconds()

    @TypeConverter
    fun epochMillisToInstant(value: Long): Instant = Instant.fromEpochMilliseconds(value)

    @TypeConverter
    fun readingModeToString(mode: ReadingMode): String = mode.name

    @TypeConverter
    fun stringToReadingMode(value: String): ReadingMode = ReadingMode.valueOf(value)

    @TypeConverter
    fun publishStatusToString(status: PublishStatus): String = status.name

    @TypeConverter
    fun stringToPublishStatus(value: String): PublishStatus = PublishStatus.valueOf(value)

    @TypeConverter
    fun readingStatusToString(status: ReadingStatus): String = status.name

    @TypeConverter
    fun stringToReadingStatus(value: String): ReadingStatus = ReadingStatus.valueOf(value)

    @TypeConverter
    fun downloadStateToString(state: DownloadState): String = state.name

    @TypeConverter
    fun stringToDownloadState(value: String): DownloadState = DownloadState.valueOf(value)

    @TypeConverter
    fun downloadErrorToString(error: DownloadError): String = error.name

    @TypeConverter
    fun stringToDownloadError(value: String): DownloadError = DownloadError.valueOf(value)
}
