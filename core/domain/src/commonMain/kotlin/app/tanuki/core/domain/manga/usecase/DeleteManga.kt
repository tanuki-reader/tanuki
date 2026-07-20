package app.tanuki.core.domain.manga.usecase

import app.tanuki.core.domain.manga.repository.MangaRepository
import kotlin.uuid.Uuid

/**
 * Use case: remove a manga from the library by id.
 *
 * Returns a [Result] so deletion failures surface explicitly rather than throwing.
 */
sealed interface DeleteManga {
    /**
     * @param mangaId Local id of the manga to delete.
     * @return [Result.success] on success, or [Result.failure] if deletion fails.
     */
    suspend operator fun invoke(mangaId: Uuid)

    class Default(
        private val mangaRepository: MangaRepository
    ) : DeleteManga {
        override suspend fun invoke(mangaId: Uuid) = mangaRepository.deleteManga(mangaId)
    }
}