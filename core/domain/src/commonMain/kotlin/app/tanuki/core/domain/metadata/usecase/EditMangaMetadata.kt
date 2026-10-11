package app.tanuki.core.domain.metadata.usecase

import app.tanuki.core.domain.metadata.CoverProvider
import app.tanuki.core.domain.metadata.MetadataProvider
import app.tanuki.core.domain.metadata.MetadataRepository
import app.tanuki.core.domain.metadata.model.CoverOption
import app.tanuki.core.domain.metadata.model.CoverOrigin
import app.tanuki.core.domain.metadata.model.DEFAULT_METADATA_LANG
import app.tanuki.core.domain.metadata.model.MatchedBy
import app.tanuki.core.domain.metadata.model.MetadataSearchResult
import app.tanuki.core.domain.metadata.model.TrackerLink
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlin.uuid.Uuid

/**
 * Use case: what the user can change about a series' metadata — search and confirm a match, unlink,
 * prefer the source's info, pick a cover. One interface because these are the one screen's actions on
 * the one [TrackerLink], and splitting them would only spread that row across six classes.
 */
interface EditMangaMetadata {
    /** AniList candidates for [title], for the user to confirm one. */
    suspend fun search(title: String): List<MetadataSearchResult>

    /** The user confirmed [anilistId] for this series; its metadata is fetched by the next enrichment. */
    suspend fun link(
        mangaId: Uuid,
        anilistId: Long
    )

    /** Forget the match (the source's info shows again until the user links once more). */
    suspend fun unlink(mangaId: Uuid)

    suspend fun setUseSourceInfo(
        mangaId: Uuid,
        useSourceInfo: Boolean
    )

    /** [url] `null` returns to the default cover. */
    suspend fun setCoverChoice(
        mangaId: Uuid,
        url: String?
    )

    /**
     * The covers to choose from: the metadata's, then each MangaDex volume's (when the series is on
     * MangaDex), then [sourceCover].
     */
    suspend fun coverOptions(
        mangaId: Uuid,
        sourceCover: String?
    ): List<CoverOption>

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val metadataRepository: MetadataRepository,
        private val provider: MetadataProvider,
        private val coverProvider: CoverProvider,
        private val applyMangaMetadata: ApplyMangaMetadata
    ) : EditMangaMetadata {
        override suspend fun search(title: String): List<MetadataSearchResult> =
            title.trim().takeIf { it.isNotEmpty() }?.let { provider.search(it) }.orEmpty()

        override suspend fun link(
            mangaId: Uuid,
            anilistId: Long
        ) {
            val current = metadataRepository.getLink(mangaId)
            metadataRepository.upsertLink(
                TrackerLink(
                    mangaId = mangaId,
                    anilistId = anilistId,
                    malId = null,
                    mangaDexId = current?.mangaDexId,
                    matchedBy = MatchedBy.User,
                    useSourceInfo = false,
                    coverChoice = null,
                    lang = current?.lang ?: DEFAULT_METADATA_LANG
                )
            )
            applyMangaMetadata(mangaId)
        }

        override suspend fun unlink(mangaId: Uuid) {
            val current = metadataRepository.getLink(mangaId) ?: return
            // Kept as a link with no ids rather than deleted, so enrichment doesn't re-link it from the
            // source on the next open.
            metadataRepository.upsertLink(current.copy(anilistId = null, malId = null, matchedBy = MatchedBy.User, coverChoice = null))
            applyMangaMetadata(mangaId)
        }

        override suspend fun setUseSourceInfo(
            mangaId: Uuid,
            useSourceInfo: Boolean
        ) {
            val current = metadataRepository.getLink(mangaId) ?: return
            metadataRepository.upsertLink(current.copy(useSourceInfo = useSourceInfo))
            applyMangaMetadata(mangaId)
        }

        override suspend fun setCoverChoice(
            mangaId: Uuid,
            url: String?
        ) {
            val current = metadataRepository.getLink(mangaId) ?: return
            metadataRepository.upsertLink(current.copy(coverChoice = url))
            applyMangaMetadata(mangaId)
        }

        override suspend fun coverOptions(
            mangaId: Uuid,
            sourceCover: String?
        ): List<CoverOption> {
            val link = metadataRepository.getLink(mangaId)
            val fromMetadata = link?.anilistId?.let { metadataRepository.getMetadata(it, link.lang) }?.coverUrl
            val volumes = link?.mangaDexId?.let { runCatching { coverProvider.volumeCovers(it) }.getOrNull() }.orEmpty()
            return buildList {
                fromMetadata?.let { add(CoverOption(it, CoverOrigin.AniList, null)) }
                addAll(volumes)
                sourceCover?.let { add(CoverOption(it, CoverOrigin.Source, null)) }
            }.distinctBy { it.url }
        }
    }
}
