package app.tanuki.features.activity.viewmodel

import app.tanuki.core.domain.history.model.HistoryEntry
import app.tanuki.core.domain.history.model.MostReadManga
import app.tanuki.core.domain.history.model.ReadingActivityPoint
import app.tanuki.core.domain.history.model.ReadingStats
import app.tanuki.core.domain.history.model.TimePeriod

sealed interface ActivityState {
    data object Loading : ActivityState

    /**
     * The loaded Activity screen. Both tabs are backed at once so switching between them is instant.
     *
     * @property history all-time read list (History tab).
     * @property period the window the period-scoped Statistics sections use.
     * @property activity gap-filled reading curve for [period]. Per-period tiles (chapters, active
     *   buckets) are derived from it in the UI.
     * @property mostRead the most-read leaderboard for [period], with per-period trend.
     * @property stats all-time aggregates (totals, streak, per-series distribution) — not period-scoped.
     */
    data class Loaded(
        val period: TimePeriod,
        val history: List<HistoryEntry>,
        val activity: List<ReadingActivityPoint>,
        val mostRead: List<MostReadManga>,
        val stats: ReadingStats
    ) : ActivityState
}