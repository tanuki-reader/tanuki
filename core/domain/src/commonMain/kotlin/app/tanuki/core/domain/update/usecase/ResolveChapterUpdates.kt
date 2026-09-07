package app.tanuki.core.domain.update.usecase

import app.tanuki.core.domain.chapter.model.Chapter
import app.tanuki.core.domain.chapter.model.isHiddenBy
import app.tanuki.core.domain.chapter.repository.ChapterRepository
import app.tanuki.core.domain.manga.model.Manga
import app.tanuki.core.domain.manga.repository.MangaRepository
import app.tanuki.core.domain.update.model.ChapterUpdate
import app.tanuki.core.domain.update.model.NewChapter
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlin.uuid.Uuid

/**
 * Resolves raw [ChapterUpdate] records into the [NewChapter]s worth showing — **the** definition of
 * "a pending update", shared by every surface that has one.
 *
 * This exists as its own injectable rather than living inside [ObserveNewChapters] because the Home
 * list is not the only consumer: the update notification announces the same thing, and if the two
 * derived their own answers they would eventually disagree — the notification would name a chapter the
 * list refuses to show. Resolving here means a rule is written once and both inherit it.
 *
 * A record survives when all of these hold:
 *  - **its series is still followed** — an update is only meaningful for a library title;
 *  - **its chapter still exists** — a chapter the source has since delisted was pruned locally, and its
 *    record is on the way out with it;
 *  - **the user has not opened it** — the reader writes a
 *    [app.tanuki.core.domain.chapter.model.ReadingProgress] row the moment a chapter opens, so its
 *    presence is exactly "already seen this one" and the list clears itself as the user reads;
 *  - **its scanlation group is not filtered out** — via the same [isHiddenBy] the chapter list and the
 *    reader use, so a group the user hid on a series can never be announced or offered from here.
 *
 * The filters are applied at *read* time, not when a record is written, which is what makes them
 * retroactive: hiding a group (or reading a chapter) drops its updates without having to find and
 * rewrite records that were already stored.
 *
 * ### Two entry points, one rule
 *
 * [invoke] answers once; [observe] keeps answering. Both project through the same private `resolve`, so
 * the filters and the ordering cannot drift apart — which is the whole reason this class exists.
 *
 * The distinction is not stylistic. Read-time filtering only makes retroactivity *observable* under
 * [observe]: with [invoke], "hiding a group drops its updates" is a statement about the next read, not
 * about the list currently on screen. So the surface that renders a list a user is looking at wants
 * [observe], and background work that resolves, acts and finishes wants [invoke] — standing up two live
 * queries to take a single emission costs more than the read it imitates. That is also why [invoke] is
 * not implemented as `observe(updates).first()`.
 *
 * Either way resolution costs two queries regardless of how many records come in: the series and the
 * chapters are each read by id in one batch. Only the chapters actually named by a record are loaded —
 * resolving them through their series would materialize every chapter of each one to find the handful
 * that are new.
 */
@Inject
class ResolveChapterUpdates(
    private val mangaRepository: MangaRepository,
    private val chapterRepository: ChapterRepository
) {
    /**
     * Resolves [updates] against the database as it is right now.
     *
     * @param updates The records to resolve, in any order.
     * @return The pending updates, newest-discovered first, and highest-numbered chapter first within a
     *   single discovery.
     */
    suspend operator fun invoke(updates: List<ChapterUpdate>): List<NewChapter> {
        if (updates.isEmpty()) return emptyList()

        return resolve(
            updates = updates,
            mangaById = mangaRepository.getMangaByIds(updates.mangaIds()).associateBy(Manga::id),
            chapterById = chapterRepository.getChaptersByIds(updates.chapterIds()).associateBy(Chapter::id),
        )
    }

    /**
     * Resolves [updates] and keeps them resolved: the result re-emits when any of the series or chapters
     * they name changes, so a chapter the user opens — or a group they hide, or a series they unfollow —
     * drops out on its own, with nothing having to invalidate the records themselves.
     *
     * The record list is fixed for the life of the returned flow, since it is what the two queries are
     * keyed by. A caller whose records change resubscribes; see [ObserveNewChapters].
     *
     * @param updates The records to resolve, in any order.
     * @return A [Flow] of the pending updates, ordered as in [invoke].
     */
    fun observe(updates: List<ChapterUpdate>): Flow<List<NewChapter>> {
        // Not merely an optimization: with no records there is nothing to key a query by, and binding an
        // empty id set would subscribe two queries that can only ever answer "nothing".
        if (updates.isEmpty()) return flowOf(emptyList())

        return combine(
            mangaRepository.observeMangaByIds(updates.mangaIds()),
            chapterRepository.observeChaptersByIds(updates.chapterIds()),
        ) { manga, chapters ->
            resolve(
                updates = updates,
                mangaById = manga.associateBy(Manga::id),
                chapterById = chapters.associateBy(Chapter::id),
            )
        }
    }

    /** The projection both entry points share — pure, so the rules are stated exactly once. */
    private fun resolve(
        updates: List<ChapterUpdate>,
        mangaById: Map<Uuid, Manga>,
        chapterById: Map<Uuid, Chapter>
    ): List<NewChapter> =
        updates
            .mapNotNull { update ->
                val manga = mangaById[update.mangaId]?.takeIf { it.isFollowed } ?: return@mapNotNull null
                val chapter = chapterById[update.chapterId]?.takeIf { it.isPending(manga) } ?: return@mapNotNull null
                NewChapter(chapter = chapter, manga = manga, discoveredAt = update.discoveredAt)
            }.sortedWith(
                compareByDescending<NewChapter> { it.discoveredAt }
                    .thenByDescending { it.chapter.chapterNumber?.value ?: 0.0 },
            )

    private fun List<ChapterUpdate>.mangaIds(): Set<Uuid> = mapTo(mutableSetOf(), ChapterUpdate::mangaId)

    private fun List<ChapterUpdate>.chapterIds(): Set<Uuid> = mapTo(mutableSetOf(), ChapterUpdate::chapterId)

    /** Library membership is exactly having been added — see [Manga.dateAdded]. */
    private val Manga.isFollowed: Boolean get() = dateAdded != null

    /** Unopened, and from a group [manga] doesn't hide. */
    private fun Chapter.isPending(manga: Manga): Boolean =
        progress == null && !isHiddenBy(manga.filteredScanlators)
}
