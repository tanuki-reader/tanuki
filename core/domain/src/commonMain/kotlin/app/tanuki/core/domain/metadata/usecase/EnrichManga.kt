package app.tanuki.core.domain.metadata.usecase

import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.domain.common.service.warn
import app.tanuki.core.domain.manga.repository.MangaRepository
import app.tanuki.core.domain.manga.model.Manga
import app.tanuki.core.domain.metadata.LocalizedTextProvider
import app.tanuki.core.domain.metadata.MetadataProvider
import app.tanuki.core.domain.metadata.MetadataRepository
import app.tanuki.core.domain.metadata.TitleLookup
import app.tanuki.core.domain.metadata.model.DEFAULT_METADATA_LANG
import app.tanuki.core.domain.metadata.model.MatchedBy
import app.tanuki.core.domain.metadata.model.SeriesMetadata
import app.tanuki.core.domain.metadata.model.TrackerLink
import app.tanuki.core.domain.source.SourceLoader
import app.tanuki.core.domain.source.model.Source
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
 *    (one details call; recorded even when the source has none, so it isn't asked again). When it has
 *    none, the series' title is searched once — AniList, then MangaDex ([TitleLookup]) — and linked only
 *    if it equals a title of exactly one series ([MatchedBy.Title]; else [MatchedBy.Unmatched]).
 * 2. **Fetch.** Per series *and* the source's language. Skipped while the cached row is fresher than
 *    [METADATA_TTL] unless [force]d. By AniList id when known, else by MyAnimeList id (and the AniList id
 *    it resolves to is stored). Outside English, the title and synopsis come from MangaDex's text in that
 *    language when the series is on MangaDex, else they stay empty and the source's own show.
 * 3. **Apply** ([ApplyMangaMetadata]): the cover and reading mode onto the `manga` row, every run, so a
 *    series cached before still gets them.
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
        private val localizedText: LocalizedTextProvider,
        private val titleLookup: TitleLookup,
        private val applyMangaMetadata: ApplyMangaMetadata,
        private val logger: Logger
    ) : EnrichManga {
        override suspend fun invoke(
            mangaId: Uuid,
            force: Boolean
        ) {
            try {
                val manga = mangaRepository.observeManga(mangaId).first() ?: return
                val source = sourceRepository.getSource(manga.source) ?: return
                val lang = source.lang.value.lowercase()
                val link =
                    (metadataRepository.getLink(mangaId) ?: linkFromSource(manga, source) ?: return)
                        // Links made before they carried a language (or after a source changed its own).
                        .let { if (it.lang == lang) it else it.copy(lang = lang).also { l -> metadataRepository.upsertLink(l) } }
                        .let { if (it.matchedBy == MatchedBy.Source && it.anilistId == null && it.malId == null) matchByTitle(it, manga) else it }
                fetch(link, force)
                applyMangaMetadata(mangaId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(TAG, "Metadata enrichment failed for manga $mangaId", e)
            }
        }

        private suspend fun fetch(
            link: TrackerLink,
            force: Boolean
        ) {
            val anilistId = link.anilistId
            if (anilistId == null && link.malId == null) return
            val cached = anilistId?.let { metadataRepository.getMetadata(it, link.lang) }
            if (!force && cached != null && Clock.System.now() - cached.fetchedAt < METADATA_TTL) return

            val fetched =
                (if (anilistId != null) provider.byAniListId(anilistId) else null)
                    ?: link.malId?.let { provider.byMalId(it) }
                    ?: return
            metadataRepository.upsertMetadata(fetched.inLanguage(link))
            if (fetched.anilistId != anilistId || (link.malId == null && fetched.malId != null)) {
                metadataRepository.upsertLink(link.copy(anilistId = fetched.anilistId, malId = link.malId ?: fetched.malId))
            }
        }

        /** [link] with the ids of the one series whose title is [manga]'s, or marked [MatchedBy.Unmatched]. */
        private suspend fun matchByTitle(
            link: TrackerLink,
            manga: Manga
        ): TrackerLink {
            val title = manga.title.value
            val matched =
                provider.search(title).singleExact(title) { it.titles }?.let { link.copy(anilistId = it.anilistId) }
                    ?: titleLookup
                        .byTitle(title)
                        .filter { it.anilistId != null || it.malId != null }
                        .singleExact(title) { it.titles }
                        ?.let { link.copy(anilistId = it.anilistId, malId = it.malId, mangaDexId = link.mangaDexId ?: it.mangaDexId) }
            val result = matched?.copy(matchedBy = MatchedBy.Title) ?: link.copy(matchedBy = MatchedBy.Unmatched)
            metadataRepository.upsertLink(result)
            return result
        }

        /** The provider's (English) metadata with [TrackerLink.lang]'s title and synopsis, or none. */
        private suspend fun SeriesMetadata.inLanguage(link: TrackerLink): SeriesMetadata {
            if (link.lang == DEFAULT_METADATA_LANG) return this
            val text = link.mangaDexId?.let { localizedText.localized(it, link.lang) }
            return copy(lang = link.lang, title = text?.title, synopsis = text?.synopsis)
        }

        /** The ids the source publishes, recorded as this series' link (possibly with none). */
        private suspend fun linkFromSource(
            manga: Manga,
            source: Source
        ): TrackerLink? {
            val sourceKey = manga.sourceMangaKey?.value ?: return null
            val details = sourceLoader.fetchMangaDetails(source.sourceId, sourceKey)
            val link =
                TrackerLink(
                    mangaId = manga.id,
                    anilistId = details.anilistId,
                    malId = details.malId,
                    // A MangaDex series is its own MangaDex id — where its volume covers come from.
                    mangaDexId = details.mangaDexId ?: sourceKey.takeIf { source.sourceId.startsWith(MANGADEX) },
                    matchedBy = MatchedBy.Source,
                    lang = source.lang.value.lowercase()
                )
            metadataRepository.upsertLink(link)
            return link
        }

        // ponytail: exact match only (case and punctuation aside); a year/format tie-break if too few match.
        private fun <T> List<T>.singleExact(
            title: String,
            titles: (T) -> List<String>
        ): T? {
            val wanted = title.normalized()
            return filter { candidate -> titles(candidate).any { it.normalized() == wanted } }.singleOrNull()
        }

        private fun String.normalized(): String = lowercase().filter(Char::isLetterOrDigit)

        private companion object {
            const val TAG = "EnrichManga"
            const val MANGADEX = "mangadex"
            val METADATA_TTL = 7.days
        }
    }
}
