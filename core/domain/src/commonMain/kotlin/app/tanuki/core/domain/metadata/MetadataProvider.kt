package app.tanuki.core.domain.metadata

import app.tanuki.core.domain.metadata.model.CoverOption
import app.tanuki.core.domain.metadata.model.MetadataSearchResult
import app.tanuki.core.domain.metadata.model.SeriesMetadata

/**
 * Where series metadata comes from (AniList today). Kept to domain types so the provider — or a second
 * one, like MyAnimeList — can be swapped behind one binding, as `SourceLoader` is for sources.
 */
interface MetadataProvider {
    /** The series with this AniList id, or `null` if the provider has none. */
    suspend fun byAniListId(id: Long): SeriesMetadata?

    /** The series the provider maps to this MyAnimeList id, or `null`. */
    suspend fun byMalId(id: Long): SeriesMetadata?

    /** Candidates for a title, best first, for the user to confirm. */
    suspend fun search(title: String): List<MetadataSearchResult>
}

/** A series' title and synopsis in one language. Either is `null` when that language has none. */
data class LocalizedText(
    val title: String?,
    val synopsis: String?
)

/** Title and synopsis in languages other than the [MetadataProvider]'s (MangaDex today). */
interface LocalizedTextProvider {
    /** [lang] is lowercase BCP-47 (`pt-br`). `null` when the series has nothing in it. */
    suspend fun localized(
        mangaDexId: String,
        lang: String
    ): LocalizedText?
}

/** A series found by title, with every name it goes by and the ids it links to. */
data class TitleCandidate(
    val titles: List<String>,
    val anilistId: Long?,
    val malId: Long?,
    val mangaDexId: String
)

/** Finds series by title beyond the [MetadataProvider] (MangaDex today, whose alt titles cover most scanlation names). */
interface TitleLookup {
    suspend fun byTitle(title: String): List<TitleCandidate>
}

/** Extra covers for a series, beyond the source's and the metadata's one. */
interface CoverProvider {
    /** One per volume, in volume order. Empty when the series has none. */
    suspend fun volumeCovers(mangaDexId: String): List<CoverOption>
}
