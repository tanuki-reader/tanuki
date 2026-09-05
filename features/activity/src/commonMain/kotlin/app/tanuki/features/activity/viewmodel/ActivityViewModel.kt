package app.tanuki.features.activity.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.domain.history.model.HistoryEntry
import app.tanuki.core.domain.history.model.MostReadManga
import app.tanuki.core.domain.history.model.ReadingActivityPoint
import app.tanuki.core.domain.history.model.TimePeriod
import app.tanuki.core.domain.history.usecase.DeleteHistory
import app.tanuki.core.domain.history.usecase.GetMostRead
import app.tanuki.core.domain.history.usecase.GetReadingActivity
import app.tanuki.core.domain.history.usecase.ObserveReadHistory
import app.tanuki.core.domain.history.usecase.ObserveReadingStats
import app.tanuki.core.navigation.MangaDetail
import app.tanuki.core.navigation.Reader
import app.tanuki.core.presentation.TanukiViewModel
import app.tanuki.features.activity.viewmodel.ActivityState.Loaded
import app.tanuki.features.activity.viewmodel.ActivityState.Loading
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

/**
 * Presentation logic for the Activity tab. Compose-free (per CLAUDE.md → MVVM): exposes state as a
 * [StateFlow] and screen-scoped navigation through [TanukiViewModel.navEvents], so the same logic drives
 * the shared Compose UI on both platforms and is unit-testable without a Compose runtime.
 *
 * Metro-injected and registered into the app's ViewModel multibinding via [ViewModelKey] +
 * [ContributesIntoMap], so `metroViewModel<ActivityViewModel>()` resolves it in the UI layer — the same
 * wiring as `LibraryViewModel`.
 *
 * Two reactive sources feed one state: [ObserveReadHistory] (the all-time History tab list) and
 * [GetReadingActivity] (the reading curve for the selected [period]). The curve re-derives whenever
 * [period] changes; [period] and its series are paired atomically so a Loaded state never shows one
 * period's label over another period's curve.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Inject
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey
class ActivityViewModel(
    observeReadHistory: ObserveReadHistory,
    observeReadingStats: ObserveReadingStats,
    getReadingActivity: GetReadingActivity,
    getMostRead: GetMostRead,
    private val deleteHistory: DeleteHistory,
    dispatcher: DispatcherProvider,
    logger: Logger
) : TanukiViewModel(dispatcher, logger) {
    val uiState: StateFlow<ActivityState>
        field = MutableStateFlow<ActivityState>(Loading)

    /** The window the reading curve charts. Drives [GetReadingActivity] below. */
    private val period = MutableStateFlow(TimePeriod.WEEK)

    init {
        // Bundle the period with everything it scopes (its curve + its leaderboard) so a Loaded state never
        // shows one period's label over another period's data.
        val periodScoped =
            period.flatMapLatest { window ->
                combine(getReadingActivity(window), getMostRead(window)) { activity, mostRead ->
                    PeriodScoped(window, activity, mostRead)
                }
            }

        combine(observeReadHistory(), observeReadingStats(), periodScoped) { history, stats, scoped ->
            Loaded(
                period = scoped.period,
                history = history,
                activity = scoped.activity,
                mostRead = scoped.mostRead,
                stats = stats
            )
        }.flowOn(dispatcher.default)
            .onEach { uiState.value = it }
            .launchIn(viewModelScope)
    }

    /** Switch the period; [GetReadingActivity] and [GetMostRead] re-derive their data for it. */
    fun onPeriodChange(period: TimePeriod) {
        this.period.value = period
    }

    /** Everything a single-period selection scopes, kept together so the emitted state stays consistent. */
    private data class PeriodScoped(
        val period: TimePeriod,
        val activity: List<ReadingActivityPoint>,
        val mostRead: List<MostReadManga>
    )

    /**
     * A history row was tapped — resume that chapter in the reader when it's still present, otherwise
     * fall back to the manga's detail screen (the chapter may have been pruned from the source).
     */
    fun onHistoryClick(entry: HistoryEntry) {
        val destination =
            entry.chapter?.let { Reader(mangaId = entry.manga.id, chapterId = it.id) }
                ?: MangaDetail(entry.manga.id)
        navigateTo(destination)
    }

    /** A history row's "series" swipe action — open the manga's detail screen instead of the chapter. */
    fun onOpenSeries(mangaId: Uuid) {
        navigateTo(MangaDetail(mangaId))
    }

    /** A history row's "delete" swipe action — remove just that read record; the list re-emits without it. */
    fun onDeleteHistory(entry: HistoryEntry) {
        viewModelScope.launch { deleteHistory(entry) }
    }
}