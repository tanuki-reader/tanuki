package app.tanuki.core.domain.manga.usecase

import app.tanuki.core.domain.manga.repository.MangaRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlin.uuid.Uuid

/**
 * Use case: replace the set of scanlation groups hidden from a manga's chapter list.
 *
 * The filter is stored per manga (see [app.tanuki.core.domain.manga.model.Manga.filteredScanlators])
 * and applied when the list is presented, never when chapters are written — hiding a group must not
 * delete its chapters, or the user's reading progress on them would cascade away with the rows.
 */
sealed interface SetFilteredScanlators {
    /**
     * @param mangaId Local id of the manga whose chapter list is being filtered.
     * @param scanlators Group names to hide; empty clears the filter.
     */
    suspend operator fun invoke(
        mangaId: Uuid,
        scanlators: Set<String>
    )

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val mangaRepository: MangaRepository
    ) : SetFilteredScanlators {
        override suspend fun invoke(
            mangaId: Uuid,
            scanlators: Set<String>
        ) = mangaRepository.setFilteredScanlators(mangaId, scanlators)
    }
}
