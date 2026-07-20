package app.tanuki.core.domain.chapter.usecase

import app.tanuki.core.domain.chapter.model.Chapter
import app.tanuki.core.domain.chapter.repository.ChapterRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

/**
 * Use case: observe a manga's chapters, re-emitting whenever they change.
 *
 * A thin pass-through over [ChapterRepository.observeChapters]; feature ViewModels depend on this
 * interface (not the repository) so ordering/derivation rules can grow here without touching the UI.
 */
sealed interface ObserveChapters {
    operator fun invoke(mangaId: Uuid): Flow<List<Chapter>>

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val chapterRepository: ChapterRepository
    ) : ObserveChapters {
        override fun invoke(mangaId: Uuid): Flow<List<Chapter>> = chapterRepository.observeChapters(mangaId)
    }
}
