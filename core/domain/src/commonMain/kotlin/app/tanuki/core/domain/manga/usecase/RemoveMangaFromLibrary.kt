package app.tanuki.core.domain.manga.usecase

import app.tanuki.core.domain.manga.repository.MangaRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlin.uuid.Uuid

/**
 * Use case: remove a manga from the user's library. Deletes the manga's library membership and clears
 * all of its category memberships, but keeps the cached manga (and its chapters) so it can still be
 * browsed and read. The reverse of [AddToLibrary]; distinct from [DeleteManga], which purges the manga
 * row entirely.
 */
sealed interface RemoveMangaFromLibrary {
    /**
     * @param mangaId Local id of the manga to remove from the library.
     */
    suspend operator fun invoke(mangaId: Uuid)

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val mangaRepository: MangaRepository
    ) : RemoveMangaFromLibrary {
        override suspend fun invoke(mangaId: Uuid) = mangaRepository.removeFromLibrary(mangaId)
    }
}
