package app.tanuki.core.domain.update.usecase

import app.tanuki.core.domain.chapter.model.Chapter
import app.tanuki.core.domain.chapter.model.isHiddenBy
import app.tanuki.core.domain.chapter.repository.ChapterRepository
import app.tanuki.core.domain.manga.model.Manga
import app.tanuki.core.domain.manga.repository.MangaRepository
import app.tanuki.core.domain.update.model.ChapterUpdate
import app.tanuki.core.domain.update.model.NewChapter
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.first

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
 * retroactive: hiding a group (or reading a chapter) immediately drops its updates, without having to
 * find and rewrite records that were already stored.
 *
 * Manga and chapters are resolved once per distinct series rather than once per record, so a burst of
 * updates on a few series stays cheap.
 */
@Inject
class ResolveChapterUpdates(
    private val mangaRepository: MangaRepository,
    private val chapterRepository: ChapterRepository
) {
    /**
     * @param updates The records to resolve, in any order.
     * @return The pending updates, newest-discovered first, and highest-numbered chapter first within a
     *   single discovery.
     */
    suspend operator fun invoke(updates: List<ChapterUpdate>): List<NewChapter> {
        if (updates.isEmpty()) return emptyList()

        val mangaIds = updates.mapTo(mutableSetOf(), ChapterUpdate::mangaId)
        // `observeManga`/`observeChapters` are Flows; `first()` takes their current value.
        val mangaById = mangaIds.associateWith { mangaRepository.observeManga(it).first() }
        val chaptersByManga = mangaIds.associateWith { chapterRepository.observeChapters(it).first() }

        return updates
            .mapNotNull { update ->
                val manga = mangaById[update.mangaId]?.takeIf { it.isFollowed } ?: return@mapNotNull null
                val chapter =
                    chaptersByManga[update.mangaId]
                        ?.firstOrNull { it.id == update.chapterId }
                        ?.takeIf { it.isPending(manga) }
                        ?: return@mapNotNull null
                NewChapter(chapter = chapter, manga = manga, discoveredAt = update.discoveredAt)
            }.sortedWith(
                compareByDescending<NewChapter> { it.discoveredAt }
                    .thenByDescending { it.chapter.chapterNumber?.value ?: 0.0 },
            )
    }

    /** Library membership is exactly having been added — see [Manga.dateAdded]. */
    private val Manga.isFollowed: Boolean get() = dateAdded != null

    /** Unopened, and from a group [manga] doesn't hide. */
    private fun Chapter.isPending(manga: Manga): Boolean =
        progress == null && !isHiddenBy(manga.filteredScanlators)
}