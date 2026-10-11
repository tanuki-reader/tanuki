package app.tanuki.core.data.repository.metadata

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

@Dao
interface MetadataDao {
    @Query("SELECT * FROM manga_tracker_link WHERE manga_id = :mangaId")
    fun observeLink(mangaId: Uuid): Flow<TrackerLinkEntity?>

    @Query("SELECT * FROM manga_tracker_link WHERE manga_id = :mangaId")
    suspend fun getLink(mangaId: Uuid): TrackerLinkEntity?

    @Upsert
    suspend fun upsertLink(link: TrackerLinkEntity)

    @Query("DELETE FROM manga_tracker_link WHERE manga_id = :mangaId")
    suspend fun deleteLink(mangaId: Uuid)

    @Query("SELECT * FROM series_metadata WHERE anilist_id = :anilistId")
    fun observeMetadata(anilistId: Long): Flow<SeriesMetadataEntity?>

    @Query("SELECT * FROM series_metadata WHERE anilist_id = :anilistId")
    suspend fun getMetadata(anilistId: Long): SeriesMetadataEntity?

    @Upsert
    suspend fun upsertMetadata(metadata: SeriesMetadataEntity)
}
