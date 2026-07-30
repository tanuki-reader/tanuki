package app.tanuki.core.domain.update.usecase

import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.update.model.NewChapter
import app.tanuki.core.domain.update.repository.ChapterUpdateRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * Use case: the chapters that arrived in the user's library and are still waiting to be opened, newest
 * first — what Home's "New Chapters" list renders.
 *
 * A thin reactive wrapper over [ResolveChapterUpdates], which owns every rule about what still counts
 * as pending. Re-emits whenever the records change **or** the underlying manga/chapters do, so reading
 * a chapter (or hiding a scanlation group) drops it from the list without anything having to invalidate
 * it explicitly.
 *
 * @see NewChapter
 */
sealed interface ObserveNewChapters {
    /**
     * @return A [Flow] of the pending updates, most-recently-discovered first. Empty until a sync finds
     *   a chapter the user doesn't already have.
     */
    operator fun invoke(): Flow<List<NewChapter>>

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val chapterUpdateRepository: ChapterUpdateRepository,
        private val resolveChapterUpdates: ResolveChapterUpdates,
        private val dispatchers: DispatcherProvider
    ) : ObserveNewChapters {
        override fun invoke(): Flow<List<NewChapter>> =
            chapterUpdateRepository
                .observeUpdates()
                .map { updates -> resolveChapterUpdates(updates) }
                .flowOn(dispatchers.io)
    }
}