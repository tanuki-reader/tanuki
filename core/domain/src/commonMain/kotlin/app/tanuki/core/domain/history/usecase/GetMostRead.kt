package app.tanuki.core.domain.history.usecase

import app.tanuki.core.domain.history.model.ChapterHistory
import app.tanuki.core.domain.history.model.MostReadManga
import app.tanuki.core.domain.history.model.MostReadManga.ReadTrend
import app.tanuki.core.domain.history.model.MostReadManga.ReadTrend.DOWN
import app.tanuki.core.domain.history.model.MostReadManga.ReadTrend.FLAT
import app.tanuki.core.domain.history.model.MostReadManga.ReadTrend.UP
import app.tanuki.core.domain.history.model.TimePeriod
import app.tanuki.core.domain.history.model.previousStartInstant
import app.tanuki.core.domain.history.model.startInstant
import app.tanuki.core.domain.history.repository.HistoryRepository
import app.tanuki.core.domain.manga.model.Manga
import app.tanuki.core.domain.manga.repository.MangaRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Use case: the "most read" leaderboard for a [TimePeriod] — every manga with at least one chapter
 * read within the current week/month/year, ranked most-read first, each carrying a trend versus the
 * previous equivalent period.
 *
 * Returns the *full* ranked list, not a top-N: how many rows to show is a presentation decision left
 * to the caller (a period is naturally bounded, so the list stays small). Truncate at the UI if needed.
 *
 * @see MostReadManga
 */
sealed interface GetMostRead {
    /**
     * @param period The window to rank within.
     * @return A reactive leaderboard (all manga read in [period], most-read first), re-emitting
     *   whenever the underlying history changes.
     */
    operator fun invoke(period: TimePeriod): Flow<List<MostReadManga>>

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val historyRepository: HistoryRepository,
        private val mangaRepository: MangaRepository
    ) : GetMostRead {
        override fun invoke(period: TimePeriod): Flow<List<MostReadManga>> {
            val currentStart = period.startInstant()
            val previousStart = period.previousStartInstant()

            return historyRepository
                .observeHistory(previousStart)
                .map { history ->
                    val current = history.countByMangaWhere { it >= currentStart }
                    val previous = history.countByMangaWhere { it < currentStart }
                    // One batch read for the whole leaderboard, not a query per ranked series.
                    val mangaById = mangaRepository.getMangaByIds(current.keys).associateBy(Manga::id)

                    current
                        .entries
                        .sortedByDescending(Map.Entry<Uuid, Int>::value)
                        .mapNotNull { (mangaId, readCount) ->
                            val manga = mangaById[mangaId] ?: return@mapNotNull null
                            MostReadManga(
                                manga = manga,
                                readCount = readCount,
                                trend = trendOf(current = readCount, previous = previous[mangaId] ?: 0)
                            )
                        }
                }
        }

        /** Reads-per-manga among entries whose `readAt` satisfies [inWindow]. */
        private inline fun List<ChapterHistory>.countByMangaWhere(inWindow: (Instant) -> Boolean): Map<Uuid, Int> =
            filter { inWindow(it.readAt) }
                .groupingBy(ChapterHistory::mangaId)
                .eachCount()

        private fun trendOf(
            current: Int,
            previous: Int
        ): ReadTrend =
            when {
                current > previous -> UP
                current < previous -> DOWN
                else -> FLAT
            }
    }
}