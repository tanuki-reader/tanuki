package app.tanuki.core.domain.metadata

import app.tanuki.core.domain.metadata.model.SeriesMetadata
import app.tanuki.core.domain.metadata.model.TrackerLink
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

/** The stored [TrackerLink]s and the metadata cache, keyed by AniList id and language. */
interface MetadataRepository {
    fun observeLink(mangaId: Uuid): Flow<TrackerLink?>

    suspend fun getLink(mangaId: Uuid): TrackerLink?

    suspend fun upsertLink(link: TrackerLink)

    suspend fun deleteLink(mangaId: Uuid)

    fun observeMetadata(
        anilistId: Long,
        lang: String
    ): Flow<SeriesMetadata?>

    suspend fun getMetadata(
        anilistId: Long,
        lang: String
    ): SeriesMetadata?

    suspend fun upsertMetadata(metadata: SeriesMetadata)
}
