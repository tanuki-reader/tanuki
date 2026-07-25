package app.tanuki.core.domain.manga.usecase

import app.tanuki.core.domain.manga.model.ReadingStatus
import app.tanuki.core.domain.manga.repository.MangaRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlin.uuid.Uuid

/**
 * Use case: set the user's reading status for a manga (see [ReadingStatus]).
 *
 * The status lives on the `library` row, which only exists once a manga is in the library, so picking a
 * status for a manga that isn't yet a member **implicitly adds it first** — the same one-tap organize-and-add
 * behavior [SetMangaCategories][app.tanuki.core.domain.category.usecase.SetMangaCategories] has: you can't
 * meaningfully be "reading" a series the library doesn't track. For a manga already in the library it just
 * updates the status, leaving the original `date_added` untouched.
 */
sealed interface SetReadingStatus {
    /**
     * @param mangaId Local id of the manga.
     * @param status The reading status to record; also adds the manga to the library when it isn't already.
     */
    suspend operator fun invoke(
        mangaId: Uuid,
        status: ReadingStatus
    )

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val mangaRepository: MangaRepository
    ) : SetReadingStatus {
        override suspend fun invoke(
            mangaId: Uuid,
            status: ReadingStatus
        ) = mangaRepository.setReadingStatus(mangaId, status)
    }
}