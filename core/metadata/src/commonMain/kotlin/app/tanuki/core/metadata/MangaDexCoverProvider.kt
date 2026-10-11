package app.tanuki.core.metadata

import app.tanuki.core.domain.metadata.CoverProvider
import app.tanuki.core.domain.metadata.model.CoverOption
import app.tanuki.core.domain.metadata.model.CoverOrigin
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.milliseconds

/** [CoverProvider] over MangaDex's public `/cover` endpoint: one cover per volume. */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class MangaDexCoverProvider internal constructor(
    private val client: HttpClient
) : CoverProvider {
    @Inject
    constructor() : this(metadataHttpClient())

    private val limiter = RateLimiter(250.milliseconds)

    override suspend fun volumeCovers(mangaDexId: String): List<CoverOption> {
        val response =
            limiter.run {
                client.get("https://api.mangadex.org/cover?manga[]=$mangaDexId&limit=100&order[volume]=asc") {
                    // MangaDex rejects requests without a User-Agent.
                    header("User-Agent", "Tanuki")
                }
            }
        check(response.status.isSuccess()) { "MangaDex covers answered HTTP ${response.status.value}" }
        return json.decodeFromString<CoverList>(response.bodyAsText()).data.mapNotNull { cover ->
            val file = cover.attributes.fileName ?: return@mapNotNull null
            CoverOption(
                url = "https://uploads.mangadex.org/covers/$mangaDexId/$file.512.jpg",
                origin = CoverOrigin.MangaDex,
                label = cover.attributes.volume
            )
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

@Serializable
private data class CoverList(val data: List<CoverDto> = emptyList())

@Serializable
private data class CoverDto(val attributes: CoverAttributes = CoverAttributes())

@Serializable
private data class CoverAttributes(val fileName: String? = null, val volume: String? = null)
