package app.tanuki.core.domain.history.usecase

import app.tanuki.core.domain.chapter.repository.ChapterRepository
import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.history.model.ChapterHistory
import app.tanuki.core.domain.history.model.HistoryEntry
import app.tanuki.core.domain.history.repository.HistoryRepository
import app.tanuki.core.domain.manga.repository.MangaRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlin.time.Instant.Companion.DISTANT_PAST

/**
 * Use case: the user's *entire* reading history, most-recent first, each entry resolved to the [Manga]
 * and [Chapter] it refers to ([HistoryEntry]) so the Activity screen can render a rich row without doing
 * any id resolution itself.
 *
 * The join lives here (not in the ViewModel), mirroring [GetMostRead]: the ViewModel stays a pure state
 * machine mapping the emitted list into UI state. Manga and chapters are resolved **once per distinct
 * manga** rather than once per history row, so a long history of the same few series stays cheap.
 *
 * @see HistoryEntry
 * @see ChapterHistory
 */
sealed interface ObserveReadHistory {
    operator fun invoke(): Flow<List<HistoryEntry>>

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val historyRepository: HistoryRepository,
        private val mangaRepository: MangaRepository,
        private val chapterRepository: ChapterRepository,
        private val dispatchers: DispatcherProvider
    ) : ObserveReadHistory {
        override fun invoke(): Flow<List<HistoryEntry>> =
            historyRepository
                .observeHistory(DISTANT_PAST)
                .map { history ->
                    // Resolve each distinct manga (and its chapters) a single time, then project every
                    // history row against those caches. `observeManga`/`observeChapters` are Flows, so we
                    // take their current value with `first()`.
                    val mangaIds = history.mapTo(mutableSetOf(), ChapterHistory::mangaId)
                    val mangaById = mangaIds.associateWith { mangaRepository.observeManga(it).first() }
                    val chaptersByManga = mangaIds.associateWith { chapterRepository.observeChapters(it).first() }

                    history.mapNotNull { entry ->
                        val manga = mangaById[entry.mangaId] ?: return@mapNotNull null
                        val chapter = chaptersByManga[entry.mangaId]?.firstOrNull { it.id == entry.chapterId }
                        HistoryEntry(
                            id = entry.id,
                            chapterId = entry.chapterId,
                            manga = manga,
                            chapter = chapter,
                            readAt = entry.readAt
                        )
                    }
                }.flowOn(dispatchers.io)
    }
}