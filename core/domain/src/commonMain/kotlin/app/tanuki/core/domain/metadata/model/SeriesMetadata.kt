package app.tanuki.core.domain.metadata.model

import app.tanuki.core.domain.manga.model.PublishStatus
import kotlin.time.Instant

/**
 * What a metadata provider (AniList) knows about a series, independent of any source. Cached by
 * [anilistId] and [lang], so every library entry of the same series in one language — whichever source it
 * came from — shares one.
 *
 * @property score average score, 0–100; `null` when the provider has too few ratings.
 * @property popularity how many users track the series on the provider.
 * @property fetchedAt when this was fetched, for the cache's freshness window.
 * @property lang the language [title] and [synopsis] are in (lowercase BCP-47). Both are `null` when the
 *   series has no text in that language, and the source's own shows instead. Everything else is the same
 *   in every language.
 * @property countryOfOrigin ISO 3166-1 alpha-2 (`JP`, `KR`, `CN`, `TW`), when known.
 */
data class SeriesMetadata(
    val anilistId: Long,
    val malId: Long?,
    val title: String?,
    val synopsis: String?,
    val coverUrl: String?,
    val bannerUrl: String?,
    val score: Int?,
    val popularity: Int?,
    val genres: List<String>,
    val tags: List<MetadataTag>,
    val status: PublishStatus,
    val year: Int?,
    val characters: List<MetadataCharacter>,
    val reviews: List<MetadataReview>,
    val recommendations: List<MetadataRecommendation>,
    val siteUrl: String,
    val fetchedAt: Instant,
    val lang: String = DEFAULT_METADATA_LANG,
    val countryOfOrigin: String? = null
)

/** The language providers write in (AniList is English). */
const val DEFAULT_METADATA_LANG = "en"

/** A descriptive tag. [rank] is the provider's relevance, 0–100; [spoiler] tags are hidden until asked. */
data class MetadataTag(
    val name: String,
    val rank: Int,
    val spoiler: Boolean
)

/** [role] is the provider's `MAIN` / `SUPPORTING` / `BACKGROUND`. */
data class MetadataCharacter(
    val name: String,
    val role: String,
    val imageUrl: String?
)

/** A user review: a one-line summary, its score (0–100) and where to read the rest. */
data class MetadataReview(
    val summary: String,
    val score: Int?,
    val url: String
)

/** A series readers of this one also like. */
data class MetadataRecommendation(
    val anilistId: Long,
    val malId: Long?,
    val title: String,
    val coverUrl: String?,
    val siteUrl: String
)

/** A candidate from a manual title search, before the user confirms it. */
data class MetadataSearchResult(
    val anilistId: Long,
    val title: String,
    val coverUrl: String?,
    val year: Int?,
    val format: String?
)

/** A cover the user can pick for a series. */
data class CoverOption(
    val url: String,
    val origin: CoverOrigin,
    /** e.g. the volume number for a MangaDex volume cover. */
    val label: String?
)

enum class CoverOrigin { Source, AniList, MangaDex }
