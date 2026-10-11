package app.tanuki.core.domain.metadata.usecase

import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.domain.common.service.warn
import app.tanuki.core.domain.manga.repository.MangaRepository
import app.tanuki.core.domain.metadata.MetadataProvider
import app.tanuki.core.domain.metadata.MetadataRepository
import app.tanuki.core.domain.metadata.model.MatchedBy
import app.tanuki.core.domain.metadata.model.TrackerLink
import app.tanuki.core.domain.source.SourceLoader
import app.tanuki.core.domain.source.repository.SourceRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

/**
 * Use case: fetch and cache a series' metadata — the **background** half of opening a manga. Callers
 * launch it alongside the chapter and details syncs and never wait on it: the page shows the source's
 * data at once and [ObserveMangaMetadata] fills the rest in when this lands.
 *
 * 1. **Link.** The stored [TrackerLink], or — the first time — the ids the source publishes for the series
 *    (one details call; recorded even when the source has none, so it isn't asked again).
 * 2. **Fetch.** Skipped while the cached metadata is fresher than [METADATA_TTL] unless [force]d. By
 *    AniList id when known, else by MyAnimeList id (and the AniList id it resolves to is stored).
 *
 * Never throws (except cancellation): enrichment is best-effort, and a failure must not surface as an
 * error on a page that already works.
 */
interface EnrichManga {
    suspend operator fun invoke(
        mangaId: Uuid,
        force: Boolean = false
    )

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val mangaRepository: MangaRepository,
        private val sourceRepository: SourceRepository,
        private val sourceLoader: SourceLoader,
        private val metadataRepository: MetadataRepository,
        private val provider: MetadataProvider,
        private val logger: Logger
    ) : EnrichManga {
        override suspend fun invoke(
            mangaId: Uuid,
            force: Boolean
        ) {
            try {
                val link = metadataRepository.getLink(mangaId) ?: linkFromSource(mangaId) ?: return
                val anilistId = link.anilistId
                if (anilistId == null && link.malId == null) return
                val cached = anilistId?.let { metadataRepository.getMetadata(it) }
                if (!force && cached != null && Clock.System.now() - cached.fetchedAt < METADATA_TTL) return

                val fetched =
                    (if (anilistId != null) provider.byAniListId(anilistId) else null)
                        ?: link.malId?.let { provider.byMalId(it) }
                        ?: return
                metadataRepository.upsertMetadata(fetched)
                if (fetched.anilistId != anilistId || (link.malId == null && fetched.malId != null)) {
                    metadataRepository.upsertLink(link.copy(anilistId = fetched.anilistId, malId = link.malId ?: fetched.malId))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(TAG, "Metadata enrichment failed for manga $mangaId", e)
            }
        }

        /** The ids the source publishes, recorded as this series' link (possibly with none). */
        private suspend fun linkFromSource(mangaId: Uuid): TrackerLink? {
            val manga = mangaRepository.observeManga(mangaId).first() ?: return null
            val sourceKey = manga.sourceMangaKey?.value ?: return null
            val source = sourceRepository.getSource(manga.source) ?: return null
            val details = sourceLoader.fetchMangaDetails(source.sourceId, sourceKey)
            val link =
                TrackerLink(
                    mangaId = mangaId,
                    anilistId = details.anilistId,
                    malId = details.malId,
                    // A MangaDex series is its own MangaDex id — where its volume covers come from.
                    mangaDexId = sourceKey.takeIf { source.sourceId.startsWith(MANGADEX) },
                    matchedBy = MatchedBy.Source
                )
            metadataRepository.upsertLink(link)
            return link
        }

        private companion object {
            const val TAG = "EnrichManga"
            const val MANGADEX = "mangadex"
            val METADATA_TTL = 7.days
        }
    }
}
