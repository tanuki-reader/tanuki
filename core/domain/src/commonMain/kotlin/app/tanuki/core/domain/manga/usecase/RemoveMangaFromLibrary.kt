package app.tanuki.core.domain.manga.usecase

import app.tanuki.core.domain.manga.repository.MangaRepository
import app.tanuki.core.domain.update.repository.ChapterUpdateRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlin.uuid.Uuid

/**
 * Use case: remove a manga from the user's library. Deletes the manga's library membership and clears
 * all of its category memberships, but keeps the cached manga (and its chapters) so it can still be
 * browsed and read. The reverse of [AddToLibrary]; distinct from [DeleteManga], which purges the manga
 * row entirely.
 *
 * Its pending chapter updates go too: an update is the note that a *followed* series gained a chapter,
 * so a series the user stopped following has nothing to announce. Without this, re-adding it later
 * would resurrect updates recorded in a previous life, dated to whenever that was.
 */
sealed interface RemoveMangaFromLibrary {
    /**
     * @param mangaId Local id of the manga to remove from the library.
     */
    suspend operator fun invoke(mangaId: Uuid)

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val mangaRepository: MangaRepository,
        private val chapterUpdateRepository: ChapterUpdateRepository
    ) : RemoveMangaFromLibrary {
        override suspend fun invoke(mangaId: Uuid) {
            mangaRepository.removeFromLibrary(mangaId)
            chapterUpdateRepository.deleteForManga(mangaId)
        }
    }
}
