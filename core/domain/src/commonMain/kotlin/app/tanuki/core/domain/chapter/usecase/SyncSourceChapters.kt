package app.tanuki.core.domain.chapter.usecase

import app.tanuki.core.domain.chapter.model.Chapter
import app.tanuki.core.domain.chapter.repository.ChapterRepository
import app.tanuki.core.domain.common.model.toTextOrNull
import app.tanuki.core.domain.manga.model.Manga
import app.tanuki.core.domain.manga.repository.MangaRepository
import app.tanuki.core.domain.source.SourceLoader
import app.tanuki.core.domain.source.model.ChapterListing
import app.tanuki.core.domain.source.repository.SourceRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.first
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Use case: refresh a manga's chapters from its source and persist them.
 *
 * The counterpart to [app.tanuki.core.domain.manga.usecase.CacheSourceManga]: that one gives a browsed
 * series a local identity, this one fills in what it can be read from. Deliberately **not** part of it —
 * caching a manga happens once (it returns early for an already-cached series), while chapters have to
 * be re-fetched every time the screen opens, and doing it on a browse tap would block navigation on a
 * network round-trip for a title the user may only be glancing at.
 *
 * Nothing is returned to the caller: writes land in [ChapterRepository], and screens observing
 * [ObserveChapters] re-render themselves. So a detail screen shows its cached chapters immediately and
 * the refreshed list replaces them when it arrives (stale-while-revalidate), rather than blocking on
 * the network with a spinner.
 *
 * ### Matching
 *
 * Remote chapters are matched to local rows by [Chapter.sourceChapterKey], so a re-sync **updates**
 * rows instead of replacing them and the user's [app.tanuki.core.domain.chapter.model.ReadingProgress]
 * (a separate table, never written here) survives. Matching on chapter *number* would be wrong on a
 * source like MangaDex, where several scanlation groups publish the same number as genuinely distinct
 * chapters.
 *
 * Throws whatever the source layer throws (no network, HTTP error, a guest-side `SourceAbiException`);
 * the caller decides how to surface a failed refresh.
 */
sealed interface SyncSourceChapters {
    /**
     * @param mangaId Local id of the manga to refresh. Unknown ids, and manga with no source key to
     *   ask about (rows cached before the key was persisted), are no-ops.
     */
    suspend operator fun invoke(mangaId: Uuid)

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val mangaRepository: MangaRepository,
        private val sourceRepository: SourceRepository,
        private val chapterRepository: ChapterRepository,
        private val sourceLoader: SourceLoader
    ) : SyncSourceChapters {
        override suspend fun invoke(mangaId: Uuid) {
            val manga = mangaRepository.observeManga(mangaId).first() ?: return
            // Both are needed to address the series in its source: the source's native id ("mangadex")
            // selects the bundle, the manga key identifies the series inside it.
            val sourceKey = manga.sourceMangaKey?.value ?: return
            val source = sourceRepository.getSource(manga.source) ?: return

            val listings = sourceLoader.fetchChapterList(source.sourceId, sourceKey)
            val local = chapterRepository.getChapters(mangaId)

            // Rows with no source key predate it being persisted; they can't be matched, so they're
            // treated as absent (and swept up by the removal pass below once they're unread).
            val localByKey = local.associateBy { it.sourceChapterKey?.value }

            val now = Clock.System.now()
            chapterRepository.upsertChapters(
                listings.map { listing -> listing.merge(localByKey[listing.chapterKey], manga, now) },
            )

            removeVanished(listings, local)
        }

        /**
         * Folds a remote listing onto its local row: an existing chapter keeps its [Chapter.id] and
         * [Chapter.dateFetch] (and, through them, its progress row) while every source-owned field is
         * refreshed; an unmatched listing becomes a new chapter fetched [now].
         */
        private fun ChapterListing.merge(
            existing: Chapter?,
            manga: Manga,
            now: Instant
        ): Chapter {
            // The source owns the chapter's permalink; when it publishes none, the series URL at least
            // opens something relevant. Identity lives in the key, so a shared URL is now harmless.
            val url = manga.url
            return existing?.copy(
                chapterNumber = number,
                name = name.toTextOrNull(),
                scanlator = scanlator.toTextOrNull(),
                sourceChapterKey = chapterKey.toTextOrNull(),
                url = url,
                dateUpload = dateUpload,
            ) ?: Chapter.new(
                mangaId = manga.id,
                chapterNumber = number,
                name = name.toTextOrNull(),
                scanlator = scanlator.toTextOrNull(),
                sourceChapterKey = chapterKey.toTextOrNull(),
                url = url,
                dateUpload = dateUpload,
                dateFetch = now,
            )
        }

        /**
         * Deletes local chapters the source no longer lists — but only untouched ones, and only when
         * the source actually returned something.
         *
         * Both guards are about not destroying user state on a bad day: an empty response is far more
         * likely a source hiccup (or a wrong key) than a series losing every chapter, and a chapter the
         * user has read or bookmarked is worth keeping around even if it's been delisted upstream —
         * deleting it would cascade the progress row away with it.
         */
        private suspend fun removeVanished(
            listings: List<ChapterListing>,
            local: List<Chapter>
        ) {
            if (listings.isEmpty()) return
            val remoteKeys = listings.mapTo(mutableSetOf(), ChapterListing::chapterKey)
            val vanished =
                local
                    .filter { it.sourceChapterKey?.value !in remoteKeys && it.progress == null }
                    .map(Chapter::id)
            if (vanished.isNotEmpty()) chapterRepository.deleteChapters(vanished)
        }
    }
}