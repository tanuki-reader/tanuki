package app.tanuki.core.domain.history.usecase

import app.tanuki.core.domain.category.model.Category
import app.tanuki.core.domain.category.repository.CategoryRepository
import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.history.model.ChapterHistory
import app.tanuki.core.domain.history.model.ReadingStats
import app.tanuki.core.domain.history.model.ReadingStats.CategoryShare
import app.tanuki.core.domain.history.model.ReadingStats.MangaShare
import app.tanuki.core.domain.history.repository.HistoryRepository
import app.tanuki.core.domain.manga.model.Manga
import app.tanuki.core.domain.manga.repository.MangaRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant.Companion.DISTANT_PAST
import kotlin.uuid.Uuid

/**
 * Use case: the user's all-time reading aggregates ([ReadingStats]) — headline totals, the day streak,
 * and the per-series distribution. Deliberately unscoped by period (the overview describes the whole
 * history), complementing the period-scoped [GetReadingActivity] and [GetMostRead].
 *
 * Series are resolved in a single batch read (as [GetMostRead]/[ObserveReadHistory] do), so a long
 * history stays cheap. Series that no longer resolve (pruned manga) are dropped from the distribution but
 * still counted in [ReadingStats.totalChapters].
 *
 * @see ReadingStats
 */
sealed interface ObserveReadingStats {
    operator fun invoke(): Flow<ReadingStats>

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val historyRepository: HistoryRepository,
        private val mangaRepository: MangaRepository,
        private val categoryRepository: CategoryRepository,
        private val dispatchers: DispatcherProvider
    ) : ObserveReadingStats {
        override fun invoke(): Flow<ReadingStats> =
            combine(
                historyRepository.observeHistory(DISTANT_PAST),
                categoryRepository.observeLibraryCategories(),
                categoryRepository.observeCategories()
            ) { history, memberships, categories ->
                history.toStats(memberships, categories)
            }.flowOn(dispatchers.io)

        private suspend fun List<ChapterHistory>.toStats(
            memberships: Map<Uuid, Set<Uuid>>,
            categories: List<Category>
        ): ReadingStats {
            if (isEmpty()) return ReadingStats.Empty
            val countByManga = groupingBy(ChapterHistory::mangaId).eachCount()
            val mangaById = mangaRepository.getMangaByIds(countByManga.keys).associateBy(Manga::id)
            val distribution =
                countByManga.entries
                    .sortedByDescending { it.value }
                    .mapNotNull { (mangaId, count) ->
                        val manga = mangaById[mangaId] ?: return@mapNotNull null
                        MangaShare(manga = manga, count = count)
                    }
            return ReadingStats(
                totalChapters = size,
                seriesCount = countByManga.size,
                dayStreak = dayStreak(),
                distribution = distribution,
                categoryShares = categoryShares(countByManga, memberships, categories)
            )
        }

        /**
         * Reads per category: each series' read count is added to every category it belongs to (categories
         * overlap), then ordered most-read first. Series with no category simply contribute nothing.
         */
        private fun categoryShares(
            countByManga: Map<Uuid, Int>,
            memberships: Map<Uuid, Set<Uuid>>,
            categories: List<Category>
        ): List<CategoryShare> {
            val categoryById = categories.associateBy(Category::id)
            val countByCategory = mutableMapOf<Uuid, Int>()
            for ((mangaId, reads) in countByManga) {
                for (categoryId in memberships[mangaId].orEmpty()) {
                    countByCategory[categoryId] = (countByCategory[categoryId] ?: 0) + reads
                }
            }
            return countByCategory.entries
                .sortedByDescending { it.value }
                .mapNotNull { (categoryId, count) ->
                    categoryById[categoryId]?.let { CategoryShare(category = it, count = count) }
                }
        }

        /**
         * Consecutive days ending today on which something was read. Counts back from today while each
         * day has at least one read; stops at the first gap. Returns 0 if nothing was read today.
         */
        private fun List<ChapterHistory>.dayStreak(zone: TimeZone = TimeZone.currentSystemDefault()): Int {
            val readDays = mapTo(mutableSetOf()) { it.readAt.toLocalDateTime(zone).date }
            var day: LocalDate = Clock.System.now().toLocalDateTime(zone).date
            var streak = 0
            while (day in readDays) {
                streak++
                day = day.minus(1, DateTimeUnit.DAY)
            }
            return streak
        }
    }
}
