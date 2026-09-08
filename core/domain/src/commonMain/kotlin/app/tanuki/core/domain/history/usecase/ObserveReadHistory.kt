package app.tanuki.core.domain.history.usecase

import app.tanuki.core.domain.chapter.model.Chapter
import app.tanuki.core.domain.chapter.repository.ChapterRepository
import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.history.model.ChapterHistory
import app.tanuki.core.domain.history.model.HistoryEntry
import app.tanuki.core.domain.history.repository.HistoryRepository
import app.tanuki.core.domain.manga.model.Manga
import app.tanuki.core.domain.manga.repository.MangaRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlin.time.Instant.Companion.DISTANT_PAST

/**
 * Use case: the user's *entire* reading history, most-recent first, each entry resolved to the [Manga]
 * and [Chapter] it refers to ([HistoryEntry]) so the Activity screen can render a rich row without doing
 * any id resolution itself.
 *
 * The join lives here (not in the ViewModel), mirroring [GetMostRead]: the ViewModel stays a pure state
 * machine mapping the emitted list into UI state. The whole history is resolved in **two** batch reads —
 * the distinct series, and exactly the chapters the rows name — rather than a query per row, which
 * matters because the reader rewrites history on every chapter it opens and this re-runs each time.
 *
 * @see HistoryEntry
 * @see ChapterHistory
 */
interface ObserveReadHistory {
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
                    // Two batch reads for the whole history, then project every row against them.
                    val mangaById =
                        mangaRepository
                            .getMangaByIds(history.mapTo(mutableSetOf(), ChapterHistory::mangaId))
                            .associateBy(Manga::id)
                    val chapterById =
                        chapterRepository
                            .getChaptersByIds(history.mapTo(mutableSetOf(), ChapterHistory::chapterId))
                            .associateBy(Chapter::id)

                    history.mapNotNull { entry ->
                        val manga = mangaById[entry.mangaId] ?: return@mapNotNull null
                        val chapter = chapterById[entry.chapterId]
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