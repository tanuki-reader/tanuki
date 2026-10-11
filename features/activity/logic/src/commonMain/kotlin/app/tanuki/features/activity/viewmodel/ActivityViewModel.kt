package app.tanuki.features.activity.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.domain.history.model.HistoryEntry
import app.tanuki.core.domain.history.model.MostReadManga
import app.tanuki.core.domain.history.model.ReadingActivityPoint
import app.tanuki.core.domain.history.model.ReadingStats
import app.tanuki.core.domain.history.model.TimePeriod
import app.tanuki.core.domain.history.usecase.DeleteHistory
import app.tanuki.core.domain.history.usecase.GetMostRead
import app.tanuki.core.domain.history.usecase.GetReadingActivity
import app.tanuki.core.domain.history.usecase.ObserveReadHistory
import app.tanuki.core.domain.history.usecase.ObserveReadingStats
import app.tanuki.core.navigation.MangaDetail
import app.tanuki.core.navigation.Reader
import app.tanuki.core.presentation.StateViewModel
import app.tanuki.features.activity.viewmodel.ActivityState.Loaded
import app.tanuki.features.activity.viewmodel.ActivityState.Loading
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * The Activity tab: what was read, day by day, and the reading statistics.
 *
 * History merges every chapter of one series read on one day into a single row, under day headers.
 * Statistics are all-time totals plus the selected [TimePeriod]'s chapters, reading days, per-day bars and
 * most-read leaderboard with each series' rank change.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Inject
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey
class ActivityViewModel(
    private val observeReadHistory: ObserveReadHistory,
    observeReadingStats: ObserveReadingStats,
    getReadingActivity: GetReadingActivity,
    getMostRead: GetMostRead,
    private val deleteHistory: DeleteHistory,
    dispatcher: DispatcherProvider,
    logger: Logger
) : StateViewModel<ActivityState>(dispatcher, logger, Loading) {
    private val tab = MutableStateFlow(ActivityTab.History)
    private val period = MutableStateFlow(TimePeriod.WEEK)

    private val periodScoped: Flow<PeriodScoped> =
        period.flatMapLatest { window ->
            combine(getReadingActivity(window), getMostRead(window)) { activity, mostRead ->
                PeriodScoped(window, activity, mostRead)
            }
        }

    override val state: Flow<ActivityState> =
        combine(tab, observeReadHistory(), observeReadingStats(), periodScoped) { tab, history, stats, scoped ->
            Loaded(tab = tab, history = history.toDays(), stats = scoped.toStats(stats))
        }

    fun onTabChange(tab: ActivityTab) {
        this.tab.value = tab
    }

    fun onPeriodChange(period: TimePeriod) {
        this.period.value = period
    }

    /** Continues the series at the latest chapter read that day (its page if the chapter is gone). */
    fun onRowClick(row: HistoryRow) {
        navigateTo(row.resumeChapterId?.let { Reader(mangaId = row.mangaId, chapterId = it) } ?: MangaDetail(row.mangaId))
    }

    fun onOpenSeries(mangaId: Uuid) {
        navigateTo(MangaDetail(mangaId))
    }

    /** Forgets every read the row stands for. */
    fun onDeleteRow(row: HistoryRow) {
        viewModelScope.launch {
            tryIo("Couldn't delete history for '${row.title}'") {
                observeReadHistory().first().filter { it.rowKey() == row.key }.forEach { deleteHistory(it) }
            }
        }
    }

    private data class PeriodScoped(
        val period: TimePeriod,
        val activity: List<ReadingActivityPoint>,
        val mostRead: List<MostReadManga>
    )

    private fun PeriodScoped.toStats(stats: ReadingStats) =
        StatsState(
            allTime = AllTimeStats(stats.totalChapters, stats.seriesCount, stats.dayStreak, stats.bestStreak),
            period = period,
            periodChapters = activity.sumOf { it.count },
            activeDays = activity.count { it.count > 0 },
            elapsedDays = activity.size,
            bars = activity.toBars(period),
            mostRead = mostRead.take(MAX_RANKED).mapIndexed { index, entry -> entry.toRanked(index + 1) }
        )

    private companion object {
        const val MAX_RANKED = 5
    }
}

private val zone: TimeZone get() = TimeZone.currentSystemDefault()

private fun HistoryEntry.localDate(): LocalDate = readAt.toLocalDateTime(zone).date

/** The row an entry merges into: its series on its local day. */
private fun HistoryEntry.rowKey(): String = "${manga.id}/${localDate()}"

/** Day groups, newest day first; within a day, rows by their latest read, newest first. */
internal fun List<HistoryEntry>.toDays(today: LocalDate = Clock.System.now().toLocalDateTime(zone).date): List<HistoryDay> =
    groupBy { it.localDate() }
        .entries
        .sortedByDescending { it.key }
        .map { (date, entries) ->
            HistoryDay(
                daysAgo = date.daysUntil(today),
                year = date.year,
                month = date.month.ordinal + 1,
                day = date.day,
                rows =
                    entries
                        .groupBy { it.manga.id }
                        .values
                        .map { it.toRow() }
                        .sortedByDescending { it.hour * 60 + it.minute }
            )
        }

private fun List<HistoryEntry>.toRow(): HistoryRow {
    val latest = maxBy { it.readAt }
    val numbers = mapNotNull { it.chapter?.chapterNumber }.distinct()
    val time = latest.readAt.toLocalDateTime(zone)
    val manga = latest.manga
    return HistoryRow(
        key = latest.rowKey(),
        mangaId = manga.id,
        title = manga.title.value,
        coverUrl = manga.cover.url?.value,
        coverHeaders = manga.cover.headers,
        chapterCount = distinctBy { it.chapterId }.size,
        firstChapter = numbers.minOrNull()?.display,
        lastChapter = numbers.maxOrNull()?.display,
        hour = time.hour,
        minute = time.minute,
        resumeChapterId = latest.chapter?.id
    )
}

/**
 * The period's bars, filled out to its **end** with empty bars for the days (months) still to come, so the
 * chart always spans the whole week, month or year: on a week's first day one bar takes a seventh of the
 * width, not all of it.
 */
internal fun List<ReadingActivityPoint>.toBars(period: TimePeriod): List<ActivityBar> {
    val start = firstOrNull()?.date ?: return emptyList()
    val step = if (period == TimePeriod.YEAR) DateTimeUnit.MONTH else DateTimeUnit.DAY
    val length =
        when (period) {
            TimePeriod.WEEK -> 7
            TimePeriod.MONTH -> start.daysUntil(start.plus(1, DateTimeUnit.MONTH))
            TimePeriod.YEAR -> 12
        }
    val counts = associate { it.date to it.count }
    return (0 until maxOf(length, size)).map { offset ->
        val date = start.plus(offset, step)
        ActivityBar(date.year, date.month.ordinal + 1, date.day, counts[date] ?: 0)
    }
}

private fun MostReadManga.toRanked(rank: Int) =
    RankedSeries(
        rank = rank,
        mangaId = manga.id,
        title = manga.title.value,
        coverUrl = manga.cover.url?.value,
        coverHeaders = manga.cover.headers,
        count = readCount,
        change =
            when (val before = previousRank) {
                null -> RankChange.New
                rank -> RankChange.Same
                else -> if (before > rank) RankChange.Up(before - rank) else RankChange.Down(rank - before)
            }
    )
