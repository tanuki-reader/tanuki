package app.tanuki.core.domain.manga.usecase

import app.tanuki.core.domain.manga.model.Manga
import app.tanuki.core.domain.manga.repository.MangaRepository

/**
 * Use case: insert or update a manga (save).
 *
 * Expresses the single "persist this manga" intent; the implementation decides whether to add or
 * update it against the repository. Returns a [Result] so persistence failures surface explicitly.
 *
 * @see Manga
 */
sealed interface UpsertManga {
    /**
     * @param manga The manga to insert or update.
     * @return [Result.success] on success, or [Result.failure] if persistence fails.
     */
    suspend operator fun invoke(manga: Manga)

    class Default(
        private val mangaRepository: MangaRepository
    ) : UpsertManga {
        override suspend fun invoke(manga: Manga) = mangaRepository.upsertManga(manga)
    }
}