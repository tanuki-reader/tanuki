package app.tanuki.core.metadata

import app.tanuki.core.domain.manga.model.PublishStatus
import app.tanuki.core.domain.metadata.MetadataProvider
import app.tanuki.core.domain.metadata.model.MetadataCharacter
import app.tanuki.core.domain.metadata.model.MetadataRecommendation
import app.tanuki.core.domain.metadata.model.MetadataReview
import app.tanuki.core.domain.metadata.model.MetadataSearchResult
import app.tanuki.core.domain.metadata.model.MetadataTag
import app.tanuki.core.domain.metadata.model.SeriesMetadata
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

/**
 * [MetadataProvider] over AniList's public GraphQL API (no key; ~90 requests a minute). One query per
 * series brings the media, its characters, reviews and recommendations together.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class AniListProvider internal constructor(
    private val client: HttpClient
) : MetadataProvider {
    @Inject
    constructor() : this(metadataHttpClient())

    private val limiter = RateLimiter(700.milliseconds)

    override suspend fun byAniListId(id: Long): SeriesMetadata? = media(buildJsonObject { put("id", id) })

    override suspend fun byMalId(id: Long): SeriesMetadata? = media(buildJsonObject { put("idMal", id) })

    override suspend fun search(title: String): List<MetadataSearchResult> {
        val data = query<SearchData>(SEARCH_QUERY, buildJsonObject { put("search", title) }) ?: return emptyList()
        return data.page.media.map {
            MetadataSearchResult(
                anilistId = it.id,
                title = it.title.preferred(),
                coverUrl = it.coverImage?.large,
                year = it.startDate?.year,
                format = it.format
            )
        }
    }

    private suspend fun media(variables: JsonObject): SeriesMetadata? =
        query<MediaData>(MEDIA_QUERY, variables)?.media?.toDomain()

    /**
     * Runs [graphql]; `null` when AniList has no such media (it answers 404 with an error body). Other
     * failures throw, for the caller to log.
     */
    private suspend inline fun <reified T> query(
        graphql: String,
        variables: JsonObject
    ): T? {
        val body = buildJsonObject {
            put("query", JsonPrimitive(graphql))
            put("variables", variables)
        }
        val response =
            limiter.run {
                client.post(ENDPOINT) {
                    contentType(ContentType.Application.Json)
                    header("Accept", "application/json")
                    setBody(body.toString())
                }
            }
        if (response.status.value == 404) return null
        check(response.status.isSuccess()) { "AniList answered HTTP ${response.status.value}" }
        return json.decodeFromString<Envelope<T>>(response.bodyAsText()).data
    }

    private companion object {
        const val ENDPOINT = "https://graphql.anilist.co"
        val json = Json { ignoreUnknownKeys = true }

        val MEDIA_QUERY =
            """
            query (${'$'}id: Int, ${'$'}idMal: Int) {
              Media(id: ${'$'}id, idMal: ${'$'}idMal, type: MANGA) {
                id idMal siteUrl status format
                title { english romaji }
                description(asHtml: false)
                coverImage { extraLarge large }
                bannerImage averageScore popularity genres
                tags { name rank isMediaSpoiler }
                startDate { year }
                characters(perPage: 12, sort: [ROLE, RELEVANCE]) { edges { role node { name { full } image { medium } } } }
                reviews(perPage: 5, sort: RATING_DESC) { nodes { summary score siteUrl } }
                recommendations(perPage: 12, sort: RATING_DESC) {
                  nodes { mediaRecommendation { id idMal siteUrl title { english romaji } coverImage { large } } }
                }
              }
            }
            """.trimIndent()

        val SEARCH_QUERY =
            """
            query (${'$'}search: String) {
              Page(perPage: 10) {
                media(search: ${'$'}search, type: MANGA, sort: SEARCH_MATCH) {
                  id format title { english romaji } coverImage { large } startDate { year }
                }
              }
            }
            """.trimIndent()
    }
}

@Serializable
private data class Envelope<T>(val data: T? = null)

@Serializable
private data class MediaData(
    @kotlinx.serialization.SerialName("Media") val media: MediaDto? = null
)

@Serializable
private data class SearchData(
    @kotlinx.serialization.SerialName("Page") val page: PageDto
)

@Serializable
private data class PageDto(val media: List<MediaDto> = emptyList())

@Serializable
private data class TitleDto(val english: String? = null, val romaji: String? = null) {
    fun preferred(): String = english?.takeIf { it.isNotBlank() } ?: romaji.orEmpty()
}

@Serializable
private data class ImageDto(val extraLarge: String? = null, val large: String? = null, val medium: String? = null)

@Serializable
private data class DateDto(val year: Int? = null)

@Serializable
private data class TagDto(val name: String, val rank: Int = 0, val isMediaSpoiler: Boolean = false)

@Serializable
private data class CharacterEdgesDto(val edges: List<CharacterEdgeDto> = emptyList())

@Serializable
private data class CharacterEdgeDto(val role: String? = null, val node: CharacterNodeDto? = null)

@Serializable
private data class CharacterNodeDto(val name: NameDto? = null, val image: ImageDto? = null)

@Serializable
private data class NameDto(val full: String? = null)

@Serializable
private data class ReviewsDto(val nodes: List<ReviewDto> = emptyList())

@Serializable
private data class ReviewDto(val summary: String? = null, val score: Int? = null, val siteUrl: String? = null)

@Serializable
private data class RecommendationsDto(val nodes: List<RecommendationNodeDto> = emptyList())

@Serializable
private data class RecommendationNodeDto(val mediaRecommendation: MediaDto? = null)

@Serializable
private data class MediaDto(
    val id: Long,
    val idMal: Long? = null,
    val siteUrl: String? = null,
    val status: String? = null,
    val format: String? = null,
    val title: TitleDto = TitleDto(),
    val description: String? = null,
    val coverImage: ImageDto? = null,
    val bannerImage: String? = null,
    val averageScore: Int? = null,
    val popularity: Int? = null,
    val genres: List<String> = emptyList(),
    val tags: List<TagDto> = emptyList(),
    val startDate: DateDto? = null,
    val characters: CharacterEdgesDto? = null,
    val reviews: ReviewsDto? = null,
    val recommendations: RecommendationsDto? = null
) {
    fun toDomain(): SeriesMetadata =
        SeriesMetadata(
            anilistId = id,
            malId = idMal,
            title = title.preferred(),
            synopsis = description?.stripHtml()?.takeIf { it.isNotBlank() },
            coverUrl = coverImage?.extraLarge ?: coverImage?.large,
            bannerUrl = bannerImage,
            score = averageScore,
            popularity = popularity,
            genres = genres,
            tags = tags.map { MetadataTag(it.name, it.rank, it.isMediaSpoiler) },
            status = status.toPublishStatus(),
            year = startDate?.year,
            characters =
                characters?.edges.orEmpty().mapNotNull { edge ->
                    val name = edge.node?.name?.full ?: return@mapNotNull null
                    MetadataCharacter(name, edge.role ?: "", edge.node.image?.medium)
                },
            reviews =
                reviews?.nodes.orEmpty().mapNotNull { review ->
                    val summary = review.summary?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    MetadataReview(summary, review.score, review.siteUrl ?: siteUrl.orEmpty())
                },
            recommendations =
                recommendations?.nodes.orEmpty().mapNotNull { node ->
                    val media = node.mediaRecommendation ?: return@mapNotNull null
                    MetadataRecommendation(media.id, media.idMal, media.title.preferred(), media.coverImage?.large, media.siteUrl.orEmpty())
                },
            siteUrl = siteUrl.orEmpty(),
            fetchedAt = Clock.System.now()
        )
}

/** AniList's `MediaStatus` → the app's [PublishStatus]. */
private fun String?.toPublishStatus(): PublishStatus =
    when (this) {
        "RELEASING" -> PublishStatus.ONGOING
        "FINISHED" -> PublishStatus.COMPLETED
        "HIATUS" -> PublishStatus.HIATUS
        "CANCELLED" -> PublishStatus.CANCELLED
        else -> PublishStatus.UNKNOWN
    }
