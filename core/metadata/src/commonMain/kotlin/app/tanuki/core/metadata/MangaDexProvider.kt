package app.tanuki.core.metadata

import app.tanuki.core.domain.metadata.CoverProvider
import app.tanuki.core.domain.metadata.LocalizedText
import app.tanuki.core.domain.metadata.LocalizedTextProvider
import app.tanuki.core.domain.metadata.TitleCandidate
import app.tanuki.core.domain.metadata.TitleLookup
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
import io.ktor.http.encodeURLParameter
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
 * title and synopsis per language ([LocalizedTextProvider], from `/manga/{id}`), and series by title
 * ([TitleLookup], from `/manga?title=`).
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, binding = binding<CoverProvider>())
@ContributesBinding(AppScope::class, binding = binding<LocalizedTextProvider>())
@ContributesBinding(AppScope::class, binding = binding<TitleLookup>())
class MangaDexProvider internal constructor(
    private val client: HttpClient
) : CoverProvider,
    LocalizedTextProvider,
    TitleLookup {
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

    /** Every content rating: the default leaves out erotica, and the source already chose what to show. */
    override suspend fun byTitle(title: String): List<TitleCandidate> {
        val ratings = RATINGS.joinToString("") { "&contentRating[]=$it" }
        val body = get("https://api.mangadex.org/manga?title=${title.encodeURLParameter()}&limit=10$ratings")
        return json.decodeFromString<MangaList>(body).data.map { manga ->
            val attributes = manga.attributes
            TitleCandidate(
                titles = (listOfNotNull(attributes.title) + attributes.altTitles).flatMap { it.texts() },
                anilistId = attributes.links.text("al")?.toLongOrNull(),
                malId = attributes.links.text("mal")?.toLongOrNull(),
                mangaDexId = manga.id
            )
        }
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
        val RATINGS = listOf("safe", "suggestive", "erotica", "pornographic")
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
private data class MangaList(val data: List<MangaDto> = emptyList())

@Serializable
private data class MangaDto(val id: String = "", val attributes: MangaAttributes = MangaAttributes())

/** Language-keyed maps, kept as raw JSON: MangaDex sends an empty one as `[]`. */
@Serializable
private data class MangaAttributes(
    val title: JsonElement? = null,
    val altTitles: List<JsonElement> = emptyList(),
    val description: JsonElement? = null,
    val links: JsonElement? = null
)

private fun JsonElement?.text(lang: String): String? = ((this as? JsonObject)?.get(lang) as? JsonPrimitive)?.contentOrNull

/** Every language's entry. */
private fun JsonElement.texts(): List<String> =
    (this as? JsonObject)?.values.orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
