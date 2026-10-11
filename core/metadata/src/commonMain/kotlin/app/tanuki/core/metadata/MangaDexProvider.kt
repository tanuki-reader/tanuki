package app.tanuki.core.metadata

import app.tanuki.core.domain.metadata.CoverProvider
import app.tanuki.core.domain.metadata.LocalizedText
import app.tanuki.core.domain.metadata.LocalizedTextProvider
import app.tanuki.core.domain.metadata.model.CoverOption
import app.tanuki.core.domain.metadata.model.CoverOrigin
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.time.Duration.Companion.milliseconds

/**
 * MangaDex's public API: volume covers ([CoverProvider], one per volume from `/cover`) and a series'
 * title and synopsis per language ([LocalizedTextProvider], from `/manga/{id}`).
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, binding = binding<CoverProvider>())
@ContributesBinding(AppScope::class, binding = binding<LocalizedTextProvider>())
class MangaDexProvider internal constructor(
    private val client: HttpClient
) : CoverProvider,
    LocalizedTextProvider {
    @Inject
    constructor() : this(metadataHttpClient())

    private val limiter = RateLimiter(250.milliseconds)

    override suspend fun volumeCovers(mangaDexId: String): List<CoverOption> {
        val body = get("https://api.mangadex.org/cover?manga[]=$mangaDexId&limit=100&order[volume]=asc")
        return json.decodeFromString<CoverList>(body).data.mapNotNull { cover ->
            val file = cover.attributes.fileName ?: return@mapNotNull null
            CoverOption(
                url = "https://uploads.mangadex.org/covers/$mangaDexId/$file.512.jpg",
                origin = CoverOrigin.MangaDex,
                label = cover.attributes.volume
            )
        }
    }

    /** The [lang] entry of `title`, else the first `altTitles` entry in it; `description[lang]`. */
    override suspend fun localized(
        mangaDexId: String,
        lang: String
    ): LocalizedText? {
        val attributes = json.decodeFromString<MangaEnvelope>(get("https://api.mangadex.org/manga/$mangaDexId")).data.attributes
        val title = attributes.title.text(lang) ?: attributes.altTitles.firstNotNullOfOrNull { it.text(lang) }
        val synopsis = attributes.description.text(lang)
        return LocalizedText(title?.takeIf { it.isNotBlank() }, synopsis?.takeIf { it.isNotBlank() })
            .takeIf { it.title != null || it.synopsis != null }
    }

    private suspend fun get(url: String): String {
        val response =
            limiter.run {
                client.get(url) {
                    // MangaDex rejects requests without a User-Agent.
                    header("User-Agent", "Tanuki")
                }
            }
        check(response.status.isSuccess()) { "MangaDex answered HTTP ${response.status.value}" }
        return response.bodyAsText()
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

@Serializable
private data class MangaEnvelope(val data: MangaDto)

@Serializable
private data class MangaDto(val attributes: MangaAttributes = MangaAttributes())

/** Language-keyed maps, kept as raw JSON: MangaDex sends an empty one as `[]`. */
@Serializable
private data class MangaAttributes(
    val title: JsonElement? = null,
    val altTitles: List<JsonElement> = emptyList(),
    val description: JsonElement? = null
)

private fun JsonElement?.text(lang: String): String? = ((this as? JsonObject)?.get(lang) as? JsonPrimitive)?.contentOrNull
