package app.tanuki.core.domain.metadata

import app.tanuki.core.domain.metadata.model.SeriesMetadata
import app.tanuki.core.domain.metadata.model.TrackerLink
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

/** The stored [TrackerLink]s and the metadata cache, keyed by AniList id. */
interface MetadataRepository {
    fun observeLink(mangaId: Uuid): Flow<TrackerLink?>

    suspend fun getLink(mangaId: Uuid): TrackerLink?

    suspend fun upsertLink(link: TrackerLink)

    suspend fun deleteLink(mangaId: Uuid)

    fun observeMetadata(anilistId: Long): Flow<SeriesMetadata?>

    suspend fun getMetadata(anilistId: Long): SeriesMetadata?

    suspend fun upsertMetadata(metadata: SeriesMetadata)
}
