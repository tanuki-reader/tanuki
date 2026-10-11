package app.tanuki.core.domain.manga.usecase

import app.tanuki.core.domain.manga.model.ReadingMode
import app.tanuki.core.domain.manga.repository.MangaRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlin.uuid.Uuid

/**
 * Use case: set how a manga's pages are laid out when reading (see [ReadingMode]).
 *
 * Unlike [SetReadingStatus], reading mode lives on the **`manga` row itself**, not the `library` row — it
 * is a reading preference that applies whether or not the series is in the library (a manga can be read
 * straight from its source), so picking one deliberately does **not** add the manga to the library. It is
 * a plain per-title override that the reader reads back when opening a chapter.
 *
 * The write targets only the reading-mode column (see [MangaRepository.setReadingMode]) rather than a
 * whole-row upsert, so a concurrent detail sync ([SyncSourceMangaDetails]) can't clobber the user's choice.
 */
interface SetReadingMode {
    /**
     * @param mangaId Local id of the manga.
     * @param mode The reading mode to record for this series.
     */
    suspend operator fun invoke(
        mangaId: Uuid,
        mode: ReadingMode
    )

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val mangaRepository: MangaRepository
    ) : SetReadingMode {
        override suspend fun invoke(
            mangaId: Uuid,
            mode: ReadingMode
        ) = mangaRepository.setReadingMode(mangaId, mode)
    }
}