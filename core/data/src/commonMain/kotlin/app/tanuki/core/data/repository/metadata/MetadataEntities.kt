package app.tanuki.core.data.repository.metadata

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import app.tanuki.core.data.repository.manga.MangaEntity
import kotlin.uuid.Uuid

/** One series' tie to its metadata — see `TrackerLink`. Deleted with the manga. */
@Entity(
    tableName = "manga_tracker_link",
    foreignKeys = [
        ForeignKey(
            entity = MangaEntity::class,
            parentColumns = ["id"],
            childColumns = ["manga_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class TrackerLinkEntity(
    @PrimaryKey @ColumnInfo(name = "manga_id") val mangaId: Uuid,
    @ColumnInfo(name = "anilist_id") val anilistId: Long?,
    @ColumnInfo(name = "mal_id") val malId: Long?,
    @ColumnInfo(name = "mangadex_id") val mangaDexId: String?,
    @ColumnInfo(name = "matched_by") val matchedBy: String,
    @ColumnInfo(name = "use_source_info") val useSourceInfo: Boolean,
    @ColumnInfo(name = "cover_choice") val coverChoice: String?,
    @ColumnInfo(name = "lang", defaultValue = "en") val lang: String,
)

/**
 * The metadata cache, keyed by AniList id and language so every library entry of a series in one language
 * shares one row. The payload is JSON: it is presentation data read whole, never queried by column.
 */
@Entity(tableName = "series_metadata", primaryKeys = ["anilist_id", "lang"])
data class SeriesMetadataEntity(
    @ColumnInfo(name = "anilist_id") val anilistId: Long,
    @ColumnInfo(name = "lang") val lang: String,
    @ColumnInfo(name = "payload") val payload: String,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long,
)
