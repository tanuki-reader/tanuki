package app.tanuki.core.domain.manga.usecase

import app.tanuki.core.domain.manga.repository.MangaRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlin.uuid.Uuid

/**
 * Use case: add a manga to the user's library. Records library membership (added now, with the default
 * reading status) while keeping the cached manga row untouched, and is a no-op if the manga is already
 * in the library. The reverse of [RemoveMangaFromLibrary].
 */
sealed interface AddToLibrary {
    /**
     * @param mangaId Local id of the manga to add to the library.
     */
    suspend operator fun invoke(mangaId: Uuid)

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val mangaRepository: MangaRepository
    ) : AddToLibrary {
        override suspend fun invoke(mangaId: Uuid) = mangaRepository.addToLibrary(mangaId)
    }
}
