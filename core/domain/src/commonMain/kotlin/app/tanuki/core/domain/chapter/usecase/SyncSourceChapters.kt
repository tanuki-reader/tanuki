package app.tanuki.core.domain.chapter.usecase

import app.tanuki.core.domain.chapter.model.Chapter
import app.tanuki.core.domain.chapter.repository.ChapterRepository
import app.tanuki.core.domain.download.usecase.AutoDownloadNewChapters
import app.tanuki.core.domain.download.usecase.DeleteChapterDownloads
import app.tanuki.core.domain.common.model.toTextOrNull
import app.tanuki.core.domain.manga.model.Manga
import app.tanuki.core.domain.manga.repository.MangaRepository
import app.tanuki.core.domain.manga.service.fetchSchedule
import app.tanuki.core.domain.source.SourceLoader
import app.tanuki.core.domain.source.model.ChapterListing
import app.tanuki.core.domain.source.repository.SourceRepository
import app.tanuki.core.domain.update.model.ChapterUpdate
import app.tanuki.core.domain.update.repository.ChapterUpdateRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.first
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
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
 * Screens don't consume the return value: writes land in [ChapterRepository], and screens observing
 * [ObserveChapters] re-render themselves. So a detail screen shows its cached chapters immediately and
 * the refreshed list replaces them when it arrives (stale-while-revalidate), rather than blocking on
 * the network with a spinner. The chapters this sync *added* are returned for the benefit of callers
 * that summarize a refresh — a library-wide update run reporting what it found.
 *
 * ### New chapters
 *
 * A chapter this sync inserted into a series the user follows is recorded as a [ChapterUpdate], which
 * is what Home's "New Chapters" list and the update notification are built from. Two conditions guard
 * that, and both are about not manufacturing news:
 *  - the series must be **in the library** — a title the user merely browsed isn't something they are
 *    following, and every chapter of it would otherwise be "new";
 *  - the series must have **already had chapters locally** — the first sync of a series is a *baseline*
 *    that establishes what was already there. Without this, adding a long-running series would announce
 *    its entire back catalogue as having just arrived.
 *
 * Recording happens here rather than in the update run so that *any* sync counts — opening a series'
 * detail screen discovers its new chapters just as well as the background job does, and the two can't
 * disagree about what "new" means.
 *
 * ### Matching
 *
 * Remote chapters are matched to local rows by [Chapter.sourceChapterKey], so a re-sync **updates**
 * rows instead of replacing them and the user's [app.tanuki.core.domain.chapter.model.ReadingProgress]
 * (a separate table, never written here) survives. Matching on chapter *number* would be wrong on a
 * source like MangaDex, where several scanlation groups publish the same number as genuinely distinct
 * chapters.
 *
 * **Gated by [Manga.chaptersFetchedAt].** The detail screen's view model asks for a refresh on every
 * open and is scoped to the navigation entry, so browsing back and forth through ten series used to be
 * ten chapter-list requests — each one serialized behind the source's mutex, stalling any browse or
 * reader page fetch queued behind it. Within [CHAPTERS_TTL] of the last successful sync this returns
 * before touching the source, which also means no `chapter_updates` rows and no auto-download pass.
 * `force` bypasses the gate for the library update run, which schedules series itself and must not be
 * gated twice.
 *
 * Throws whatever the source layer throws (no network, HTTP error, a guest-side `SourceAbiException`);
 * the caller decides how to surface a failed refresh.
 */
sealed interface SyncSourceChapters {
    /**
     * @param mangaId Local id of the manga to refresh. Unknown ids, and manga with no source key to
     *   ask about (rows cached before the key was persisted), are no-ops.
     * @return The chapters this sync added, empty when it added none (including every no-op case).
     *   Note this is what was *inserted*, not what was recorded as an update — a baseline sync adds
     *   chapters without any of them being news.
     * @param force Skip the [Manga.chaptersFetchedAt] staleness check and always ask the source. For
     *   callers that already decided this series is due; a user-facing open should leave it `false`.
     */
    suspend operator fun invoke(
        mangaId: Uuid,
        force: Boolean = false
    ): List<Chapter>

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val mangaRepository: MangaRepository,
        private val sourceRepository: SourceRepository,
        private val chapterRepository: ChapterRepository,
        private val chapterUpdateRepository: ChapterUpdateRepository,
        private val sourceLoader: SourceLoader,
        private val autoDownloadNewChapters: AutoDownloadNewChapters,
        private val deleteChapterDownloads: DeleteChapterDownloads
    ) : SyncSourceChapters {
        override suspend fun invoke(
            mangaId: Uuid,
            force: Boolean
        ): List<Chapter> {
            val manga = mangaRepository.observeManga(mangaId).first() ?: return emptyList()
            val now = Clock.System.now()
            // Ahead of the fetch, and so ahead of every side effect below it — a suppressed sync writes
            // no chapters, records no updates and queues no downloads. A never-synced row has no stamp,
            // which reads as "ask now", so an upgraded install behaves as it did before.
            if (!force && manga.chaptersFetchedAt?.let { now - it < CHAPTERS_TTL } == true) return emptyList()
            // Both are needed to address the series in its source: the source's native id ("mangadex")
            // selects the bundle, the manga key identifies the series inside it.
            val sourceKey = manga.sourceMangaKey?.value ?: return emptyList()
            val source = sourceRepository.getSource(manga.source) ?: return emptyList()

            val listings = sourceLoader.fetchChapterList(source.sourceId, sourceKey)
            val local = chapterRepository.getChapters(mangaId)

            // Rows with no source key predate it being persisted; they can't be matched, so they're
            // treated as absent (and swept up by the removal pass below once they're unread).
            val localByKey = local.associateBy { it.sourceChapterKey?.value }

            // Captured before anything is written: a merged chapter reuses its local row's id, so
            // "was not already held" is what identifies the ones this sync minted — and that has to be
            // decided against the library as it was, not as the write leaves it.
            val localIds = local.mapTo(mutableSetOf(), Chapter::id)

            val merged = listings.map { listing -> listing.merge(localByKey[listing.chapterKey], manga, now) }
            chapterRepository.upsertChapters(merged)

            removeVanished(listings, local)

            val added = merged.filter { it.id !in localIds }
            recordUpdates(added, manga, isBaseline = local.isEmpty(), now = now)
            // Both callers of this use case — the scheduled library update and a manual pull on a series
            // page — mean the same thing by "new chapter arrived", so the auto-download rule is applied
            // here rather than in each of them. It no-ops unless the user has asked for it.
            autoDownloadNewChapters(manga, added)

            // Last, and only on the happy path: a throw from the source layer leaves the stamp alone, so
            // a failed refresh is retried on the next open rather than suppressed for the whole TTL.
            //
            // The schedule is derived here rather than by the library run that asked for the sync,
            // because `merged` is the chapter list it needs and it is already in hand — computing it a
            // caller away would mean re-reading and re-materializing every chapter of the series, on
            // every series, on every run. It also keeps the schedule of a series the user reads by hand
            // as fresh as one the run touches.
            val schedule = fetchSchedule(manga, merged, now)
            mangaRepository.recordChapterSync(manga.id, now, schedule.nextUpdateAt, schedule.fetchInterval)
            return added
        }

        private companion object {
            /**
             * How long a chapter sync's result is considered fresh.
             *
             * Short, because this is the field that actually moves: a series the user is following can
             * publish at any hour, and a reader who opens it expecting today's chapter must not be told
             * yesterday's list. Half an hour collapses the burst of opens that a browsing session
             * produces — the case this gate exists for — while leaving a deliberate return to a series
             * later in the day free to fetch. The library update run is what covers the long tail; it
             * passes `force` and is scheduled on its own interval.
             */
            val CHAPTERS_TTL = 30.minutes
        }

        /**
         * Notes [added] chapters as pending updates, when this sync was a real refresh of a followed
         * series rather than its baseline — see the class docs for why both guards exist.
         */
        private suspend fun recordUpdates(
            added: List<Chapter>,
            manga: Manga,
            isBaseline: Boolean,
            now: Instant
        ) {
            if (isBaseline || manga.dateAdded == null || added.isEmpty()) return
            chapterUpdateRepository.recordUpdates(
                added.map { ChapterUpdate(chapterId = it.id, mangaId = manga.id, discoveredAt = now) },
            )
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
            if (vanished.isEmpty()) return
            // Before the rows, and that order is load-bearing twice over: deleting the chapter cascades
            // its download record away, and this resolves each chapter *through* that record — so
            // afterwards there would be nothing left to say which files to remove, and the bytes of a
            // chapter nothing can any longer name would sit there until the startup sweep found them.
            deleteChapterDownloads(vanished)
            chapterRepository.deleteChapters(vanished)
        }
    }
}