package app.tanuki.core.data.repository.library

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import app.tanuki.core.data.repository.manga.MangaEntity
import app.tanuki.core.domain.manga.model.ReadingStatus
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The user's library membership for a manga: the *presence* of this row means the manga is in the
 * library. Holds the state that only exists once added — when it was added and the user's reading
 * status. [id] is both the primary key and a cascade FK to `manga(id)`, so it is a 1:1 side table on
 * top of [MangaEntity], and removing the manga removes the membership.
 */
@Entity(
    tableName = "library",
    foreignKeys = [
        ForeignKey(
            entity = MangaEntity::class,
            parentColumns = ["id"],
            childColumns = ["id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class LibraryEntryEntity(
    @PrimaryKey val id: Uuid,
    @ColumnInfo(name = "date_added") val dateAdded: Instant,
    @ColumnInfo(name = "reading_status") val readingStatus: ReadingStatus,
)