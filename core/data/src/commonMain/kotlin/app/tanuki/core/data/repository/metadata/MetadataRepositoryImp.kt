package app.tanuki.core.data.repository.metadata

import app.tanuki.core.domain.manga.model.PublishStatus
import app.tanuki.core.domain.metadata.MetadataRepository
import app.tanuki.core.domain.metadata.model.MatchedBy
import app.tanuki.core.domain.metadata.model.MetadataCharacter
import app.tanuki.core.domain.metadata.model.MetadataRecommendation
import app.tanuki.core.domain.metadata.model.MetadataReview
import app.tanuki.core.domain.metadata.model.MetadataTag
import app.tanuki.core.domain.metadata.model.SeriesMetadata
import app.tanuki.core.domain.metadata.model.TrackerLink
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Room-backed [MetadataRepository]. The metadata payload is stored as JSON (see [SeriesMetadataEntity]). */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class MetadataRepositoryImp(
    private val dao: MetadataDao
) : MetadataRepository {
    override fun observeLink(mangaId: Uuid): Flow<TrackerLink?> =
        dao.observeLink(mangaId).distinctUntilChanged().map { it?.toDomain() }

    override suspend fun getLink(mangaId: Uuid): TrackerLink? = dao.getLink(mangaId)?.toDomain()

    override suspend fun upsertLink(link: TrackerLink) = dao.upsertLink(link.toEntity())

    override suspend fun deleteLink(mangaId: Uuid) = dao.deleteLink(mangaId)

    override fun observeMetadata(anilistId: Long): Flow<SeriesMetadata?> =
        dao.observeMetadata(anilistId).distinctUntilChanged().map { it?.toDomain() }

    override suspend fun getMetadata(anilistId: Long): SeriesMetadata? = dao.getMetadata(anilistId)?.toDomain()

    override suspend fun upsertMetadata(metadata: SeriesMetadata) = dao.upsertMetadata(metadata.toEntity())
}

private val json = Json { ignoreUnknownKeys = true }

private fun TrackerLinkEntity.toDomain() =
    TrackerLink(
        mangaId = mangaId,
        anilistId = anilistId,
        malId = malId,
        mangaDexId = mangaDexId,
        matchedBy = MatchedBy.entries.firstOrNull { it.name == matchedBy } ?: MatchedBy.Source,
        useSourceInfo = useSourceInfo,
        coverChoice = coverChoice
    )

private fun TrackerLink.toEntity() =
    TrackerLinkEntity(mangaId, anilistId, malId, mangaDexId, matchedBy.name, useSourceInfo, coverChoice)

/**
 * The cached payload's shape. Its own type, not the domain one, so the stored JSON is a contract this
 * layer owns; an unreadable row (an older shape) is treated as a cache miss.
 */
@Serializable
private data class MetadataPayload(
    val malId: Long? = null,
    val title: String,
    val synopsis: String? = null,
    val coverUrl: String? = null,
    val bannerUrl: String? = null,
    val score: Int? = null,
    val popularity: Int? = null,
    val genres: List<String> = emptyList(),
    val tags: List<Tag> = emptyList(),
    val status: String = PublishStatus.UNKNOWN.name,
    val year: Int? = null,
    val characters: List<Character> = emptyList(),
    val reviews: List<Review> = emptyList(),
    val recommendations: List<Recommendation> = emptyList(),
    val siteUrl: String = ""
) {
    @Serializable
    data class Tag(val name: String, val rank: Int, val spoiler: Boolean)

    @Serializable
    data class Character(val name: String, val role: String, val imageUrl: String? = null)

    @Serializable
    data class Review(val summary: String, val score: Int? = null, val url: String)

    @Serializable
    data class Recommendation(
        val anilistId: Long,
        val malId: Long? = null,
        val title: String,
        val coverUrl: String? = null,
        val siteUrl: String
    )
}

private fun SeriesMetadataEntity.toDomain(): SeriesMetadata? {
    val payload = runCatching { json.decodeFromString<MetadataPayload>(this.payload) }.getOrNull() ?: return null
    return SeriesMetadata(
        anilistId = anilistId,
        malId = payload.malId,
        title = payload.title,
        synopsis = payload.synopsis,
        coverUrl = payload.coverUrl,
        bannerUrl = payload.bannerUrl,
        score = payload.score,
        popularity = payload.popularity,
        genres = payload.genres,
        tags = payload.tags.map { MetadataTag(it.name, it.rank, it.spoiler) },
        status = PublishStatus.entries.firstOrNull { it.name == payload.status } ?: PublishStatus.UNKNOWN,
        year = payload.year,
        characters = payload.characters.map { MetadataCharacter(it.name, it.role, it.imageUrl) },
        reviews = payload.reviews.map { MetadataReview(it.summary, it.score, it.url) },
        recommendations =
            payload.recommendations.map { MetadataRecommendation(it.anilistId, it.malId, it.title, it.coverUrl, it.siteUrl) },
        siteUrl = payload.siteUrl,
        fetchedAt = Instant.fromEpochMilliseconds(fetchedAt)
    )
}

private fun SeriesMetadata.toEntity() =
    SeriesMetadataEntity(
        anilistId = anilistId,
        payload =
            json.encodeToString(
                MetadataPayload(
                    malId = malId,
                    title = title,
                    synopsis = synopsis,
                    coverUrl = coverUrl,
                    bannerUrl = bannerUrl,
                    score = score,
                    popularity = popularity,
                    genres = genres,
                    tags = tags.map { MetadataPayload.Tag(it.name, it.rank, it.spoiler) },
                    status = status.name,
                    year = year,
                    characters = characters.map { MetadataPayload.Character(it.name, it.role, it.imageUrl) },
                    reviews = reviews.map { MetadataPayload.Review(it.summary, it.score, it.url) },
                    recommendations =
                        recommendations.map {
                            MetadataPayload.Recommendation(it.anilistId, it.malId, it.title, it.coverUrl, it.siteUrl)
                        },
                    siteUrl = siteUrl
                )
            ),
        fetchedAt = fetchedAt.toEpochMilliseconds()
    )
