package app.tanuki.core.domain.manga.usecase

import app.tanuki.core.domain.common.model.toTextOrNull
import app.tanuki.core.domain.manga.model.Manga
import app.tanuki.core.domain.manga.model.MangaCover
import app.tanuki.core.domain.manga.repository.MangaRepository
import app.tanuki.core.domain.source.SourceLoader
import app.tanuki.core.domain.source.repository.SourceRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.first
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

/**
 * Use case: refresh one manga's **source-owned** fields (description, author, cover, status) from its
 * source and persist them.
 *
 * The manga counterpart to [app.tanuki.core.domain.chapter.usecase.SyncSourceChapters]. A browse
 * listing carries only what a source's *catalog* endpoint returns, which a source may deliberately keep
 * narrower than the full record — a cover grid renders a title and an image, so a source is free to skip
 * parsing the rest (MangaDex's descriptions alone are ~38% of a browse response). Without this use case
 * that reduction would be user-visible, because a cached listing was previously the *only* thing the
 * detail screen ever showed. Here the screen asks for the rest: one series, one request, once per open.
 *
 * Nothing is returned: the write lands in [MangaRepository] and the screen observing the manga
 * re-renders itself, so a detail screen shows its cached row immediately and the fuller one replaces it
 * when it arrives (stale-while-revalidate), rather than blocking on the network.
 *
 * Only source-owned fields are touched — and only *those* columns are written, through
 * [MangaRepository.setSourceDetails] rather than a whole-row upsert of the snapshot this started from.
 * That snapshot is read before the network call and stored after it, so a whole-row write would revert
 * anything that happened in between: a reading mode the user picked while the request was in flight, or
 * the chapter-sync stamp from [MangaRepository.markChaptersFetched] — and the detail screen runs both
 * syncs at once, so that overlap is the normal case. Local identity and user state can therefore never
 * be touched at all, rather than being copied back and hoped over.
 *
 * **Gated by [Manga.detailsFetchedAt].** The screen's view model asks for a refresh on every open, and
 * it is scoped to the navigation entry, so browsing back and forth through ten series used to be ten
 * requests. Within [DETAILS_TTL] of the last successful sync this returns without calling the source.
 * A day is a long TTL on purpose: a synopsis, an author credit and a cover are close to immutable next
 * to a chapter list. `force` bypasses it for callers that do their own scheduling.
 *
 * Throws whatever the source layer throws (no network, HTTP error, a guest-side `SourceAbiException`);
 * the caller decides how to surface a failed refresh.
 */
sealed interface SyncSourceMangaDetails {
    /**
     * @param mangaId Local id of the manga to refresh. Unknown ids, and manga with no source key to
     *   ask about (rows cached before the key was persisted), are no-ops.
     * @param force Skip the [Manga.detailsFetchedAt] staleness check and always ask the source. For
     *   callers that already decided this series is due; a user-facing open should leave it `false`.
     */
    suspend operator fun invoke(
        mangaId: Uuid,
        force: Boolean = false
    )

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val mangaRepository: MangaRepository,
        private val sourceRepository: SourceRepository,
        private val sourceLoader: SourceLoader
    ) : SyncSourceMangaDetails {
        override suspend fun invoke(
            mangaId: Uuid,
            force: Boolean
        ) {
            val manga = mangaRepository.observeManga(mangaId).first() ?: return
            val now = Clock.System.now()
            // Ahead of every side effect, so a suppressed refresh is exactly a no-op. A never-synced
            // row has no stamp, which reads as "ask now" — that is what makes an upgraded install
            // behave as it did before.
            if (!force && manga.detailsFetchedAt?.let { now - it < DETAILS_TTL } == true) return
            // Both are needed to address the series in its source: the source's native id ("mangadex")
            // selects the bundle, the manga key identifies the series inside it.
            val sourceKey = manga.sourceMangaKey?.value ?: return
            val source = sourceRepository.getSource(manga.source) ?: return

            val details = sourceLoader.fetchMangaDetails(source.sourceId, sourceKey)

            mangaRepository.setSourceDetails(
                mangaId = manga.id,
                // A source that returns a blank field keeps whatever is already stored rather than
                // erasing it: the catalog entry is a legitimate source of these too, and a detail
                // endpoint that omits one is likelier incomplete than authoritative.
                title = details.title.toTextOrNull() ?: manga.title,
                author = details.author.toTextOrNull() ?: manga.author,
                description = details.description.toTextOrNull() ?: manga.description,
                publishStatus = details.status,
                // Only re-stamp the cover when the URL actually changed — lastModified is the image
                // loader's cache key, so bumping it on every open would refetch an unchanged cover.
                cover =
                    if (details.coverUrl != null && details.coverUrl != manga.cover.url) {
                        MangaCover(
                            mangaId = manga.id,
                            url = details.coverUrl,
                            lastModified = Clock.System.now(),
                            headers = details.coverHeaders
                        )
                    } else {
                        // The URL is unchanged, but the headers it needs may have been added by a
                        // source update — adopt those without re-stamping lastModified, which would
                        // needlessly evict a cover the loader already has.
                        manga.cover.copy(headers = details.coverHeaders.ifEmpty { manga.cover.headers })
                    }
            )

            // Last, and only on the happy path: a throw from the source layer leaves the stamp alone, so
            // a failed refresh is retried on the next open instead of being suppressed for a day.
            mangaRepository.markDetailsFetched(manga.id, now)
        }

        private companion object {
            /**
             * How long a details sync's result is considered fresh.
             *
             * Far longer than the chapter-list equivalent because the fields differ in kind, not degree:
             * a synopsis, an author credit and a cover are effectively immutable, and the cost of being
             * a day late on one is invisible. A user who needs it sooner has pull-to-refresh, which
             * passes `force`.
             */
            val DETAILS_TTL = 24.hours
        }
    }
}