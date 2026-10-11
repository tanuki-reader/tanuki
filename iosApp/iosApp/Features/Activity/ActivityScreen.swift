import Charts
import Shared
import SwiftUI

/// The Activity tab: History (one row per series per day, under day headers) and Statistics (all-time
/// tiles, the period's tiles, per-day bars, most read). Everything comes from `ActivityViewModel`; dates
/// and times are only formatted here, in the user's locale.
struct ActivityScreen: View {
    @Binding var path: [Route]
    @State private var model = ScreenModel({ $0.activityViewModel() }, uiState: \.uiState)

    var body: some View {
        content
            .background(Color.tk.canvas)
            .navigationTitle(tr("activity_title", table: .activity))
            .task { await model.observe() }
            .navEvents(model.vm.navEvents, path: $path)
    }

    @ViewBuilder
    private var content: some View {
        switch onEnum(of: model.state) {
        case .loading:
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        case let .loaded(loaded):
            List {
                Picker(selection: Binding(get: { loaded.tab }, set: { model.vm.onTabChange(tab: $0) })) {
                    Text(tr("activity_tab_history", table: .activity)).tag(ActivityTab.history)
                    Text(tr("activity_tab_curve", table: .activity)).tag(ActivityTab.statistics)
                } label: {
                    EmptyView()
                }
                .pickerStyle(.segmented)
                .listRowBackground(Color.clear)
                .listRowInsets(EdgeInsets())
                .listRowSeparator(.hidden)

                switch loaded.tab {
                case .history: HistorySections(days: loaded.history, vm: model.vm)
                case .statistics: StatisticsSections(stats: loaded.stats, vm: model.vm)
                }
            }
            .scrollContentBackground(.hidden)
        }
    }
}

// MARK: - History

private struct HistorySections: View {
    let days: [HistoryDay]
    let vm: ActivityViewModel

    var body: some View {
        if days.isEmpty {
            ContentUnavailableView(tr("activity_history_empty", table: .activity), systemImage: "clock")
                .listRowBackground(Color.clear)
        }
        ForEach(days, id: \.self) { day in
            Section(dayLabel(day)) {
                ForEach(day.rows, id: \.key) { row in
                    Button { vm.onRowClick(row: row) } label: { HistoryRowView(row: row, day: day) }
                        .buttonStyle(.plain)
                        .listRowBackground(Color.tk.surfaceRaised)
                        .swipeActions(edge: .trailing) {
                            Button(role: .destructive) { vm.onDeleteRow(row: row) } label: {
                                Label(tr("activity_action_delete", table: .activity), systemImage: "trash")
                            }
                            Button { vm.onOpenSeries(mangaId: row.mangaId) } label: {
                                Label(tr("activity_action_series", table: .activity), systemImage: "book")
                            }
                        }
                }
            }
        }
    }

    /// "Today", "Yesterday", "3 days ago", then the date once it's a week or more back.
    private func dayLabel(_ day: HistoryDay) -> String {
        switch day.daysAgo {
        case ...0: tr("activity_day_today", table: .activity)
        case 1: tr("activity_day_yesterday", table: .activity)
        case 2..<7: tr("activity_days_ago", Int(day.daysAgo), table: .activity)
        default: date(Int(day.year), Int(day.month), Int(day.day)).formatted(.dateTime.month(.abbreviated).day())
        }
    }
}

private struct HistoryRowView: View {
    let row: Shared.HistoryRow
    let day: HistoryDay

    var body: some View {
        HStack(spacing: TK.Space.s3) {
            MangaCover(seed: row.mangaId.description, url: row.coverUrl, headers: row.coverHeaders)
                .frame(width: 40)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: TK.Space.s1) {
                Text(row.title)
                    .tkStyle(.body)
                    .foregroundStyle(Color.tk.ink)
                    .lineLimit(1)
                Text(meta)
                    .tkStyle(.caption)
                    .foregroundStyle(Color.tk.inkMuted)
                    .lineLimit(1)
            }
            Spacer(minLength: TK.Space.s2)
            Text(time)
                .tkStyle(.caption)
                .foregroundStyle(Color.tk.inkMuted)
                .monospacedDigit()
        }
        .contentShape(Rectangle())
    }

    /// "4 chapters · Ch. 418–421", or just the count when the chapters have no numbers.
    private var meta: String {
        let count = tr("activity_chapters", Int(row.chapterCount), table: .activity)
        guard let first = row.firstChapter else { return count }
        let last = row.lastChapter ?? first
        let range = first == last
            ? tr("activity_chapter_single", first, table: .activity)
            : tr("activity_chapter_range", first, last, table: .activity)
        return "\(count) · \(range)"
    }

    private var time: String {
        date(Int(day.year), Int(day.month), Int(day.day), hour: Int(row.hour), minute: Int(row.minute))
            .formatted(date: .omitted, time: .shortened)
    }
}

// MARK: - Statistics

private struct StatisticsSections: View {
    let stats: StatsState
    let vm: ActivityViewModel
    @State private var selected: Date?

    private var isYear: Bool { stats.period == .year }

    var body: some View {
        Section(tr("activity_all_time", table: .activity)) {
            Grid(horizontalSpacing: TK.Space.s3) {
                GridRow {
                    tile(String(stats.allTime.chapters), tr("activity_stat_chapters", table: .activity))
                    tile(String(stats.allTime.series), tr("activity_stat_series", table: .activity))
                    tile(
                        tr("activity_streak_days", Int(stats.allTime.bestStreak), table: .activity),
                        tr("activity_stat_best_streak", table: .activity),
                        detail: tr("activity_current_streak", Int(stats.allTime.currentStreak), table: .activity)
                    )
                }
            }
            .plainRow()
        }

        Section {
            Picker(selection: Binding(get: { stats.period }, set: { vm.onPeriodChange(period: $0) })) {
                Text(tr("activity_period_week", table: .activity)).tag(TimePeriod.week)
                Text(tr("activity_period_month", table: .activity)).tag(TimePeriod.month)
                Text(tr("activity_period_year", table: .activity)).tag(TimePeriod.year)
            } label: {
                EmptyView()
            }
            .pickerStyle(.segmented)
            .plainRow()
        }

        Section(periodTitle) {
            Grid(horizontalSpacing: TK.Space.s3) {
                GridRow {
                    tile(String(stats.periodChapters), periodChaptersLabel)
                    tile(
                        String(stats.activeDays),
                        tr(isYear ? "activity_stat_months_reading" : "activity_stat_days_reading", Int(stats.elapsedDays), table: .activity)
                    )
                }
            }
            .plainRow()
        }

        Section(tr(isYear ? "activity_chapters_per_month" : "activity_chapters_per_day", table: .activity)) {
            if stats.periodChapters == 0 {
                Text(tr("activity_curve_empty", table: .activity))
                    .tkStyle(.body)
                    .foregroundStyle(Color.tk.inkMuted)
                    .listRowBackground(Color.tk.surfaceRaised)
            } else {
                chart.plainRow()
            }
        }

        if !stats.mostRead.isEmpty {
            Section(tr("activity_section_most_read", table: .activity)) {
                ForEach(stats.mostRead, id: \.mangaId) { item in
                    Button { vm.onOpenSeries(mangaId: item.mangaId) } label: { MostReadRowView(item: item) }
                        .buttonStyle(.plain)
                        .listRowBackground(Color.tk.surfaceRaised)
                }
            }
        }
    }

    // MARK: Chart

    private var unit: Calendar.Component { isYear ? .month : .day }

    private var chart: some View {
        VStack(alignment: .leading, spacing: TK.Space.s2) {
            Text(selectionLabel ?? " ")
                .tkStyle(.caption)
                .foregroundStyle(Color.tk.inkMuted)
            Chart(stats.bars, id: \.self) { bar in
                BarMark(
                    x: .value("Date", barDate(bar), unit: unit),
                    y: .value("Chapters", Int(bar.count))
                )
                .foregroundStyle(isSelected(bar) ? Color.tk.accent : Color.tk.accent.opacity(0.35))
                .cornerRadius(3)
            }
            .chartXSelection(value: $selected)
            .chartXAxis {
                if isYear {
                    AxisMarks(values: .stride(by: .month)) { _ in AxisValueLabel(format: .dateTime.month(.narrow)) }
                } else if stats.period == .week {
                    AxisMarks(values: .stride(by: .day)) { _ in AxisValueLabel(format: .dateTime.weekday(.narrow)) }
                } else {
                    AxisMarks(values: .stride(by: .day, count: 7)) { _ in AxisValueLabel(format: .dateTime.day()) }
                }
            }
            .chartYAxis(.hidden)
            .frame(height: 140)
        }
        // The row has no insets (no cell chrome), so the chart brings its own margins: without them the
        // first bar's centred axis label and the selection caption were clipped at the screen's edge.
        .padding(.horizontal, TK.Space.s5)
        .padding(.vertical, TK.Space.s2)
    }

    private var selectedBar: ActivityBar? {
        guard let selected else { return nil }
        return stats.bars.first { Calendar.current.isDate(barDate($0), equalTo: selected, toGranularity: unit) }
    }

    private func isSelected(_ bar: ActivityBar) -> Bool { selectedBar == bar }

    /// "Oct 6 · 18 chapters" for the bar under the finger.
    private var selectionLabel: String? {
        // An empty day (or one still to come) has nothing to say.
        guard let bar = selectedBar, bar.count > 0 else { return nil }
        let date = barDate(bar).formatted(isYear ? .dateTime.month(.wide) : .dateTime.month(.abbreviated).day())
        return "\(date) · \(tr("activity_chapters", Int(bar.count), table: .activity))"
    }

    private func barDate(_ bar: ActivityBar) -> Date { date(Int(bar.year), Int(bar.month), Int(bar.day)) }

    // MARK: Tiles

    private var periodTitle: String {
        switch stats.period {
        case .week: tr("activity_this_week", table: .activity)
        case .month: tr("activity_this_month", table: .activity)
        case .year: tr("activity_this_year", table: .activity)
        }
    }

    private var periodChaptersLabel: String {
        switch stats.period {
        case .week: tr("activity_stat_chapters_week", table: .activity)
        case .month: tr("activity_stat_chapters_month", table: .activity)
        case .year: tr("activity_stat_chapters_year", table: .activity)
        }
    }

    private func tile(_ value: String, _ label: String, detail: String? = nil) -> some View {
        VStack(alignment: .leading, spacing: TK.Space.s1) {
            Text(value)
                .tkStyle(.titleL)
                .foregroundStyle(Color.tk.ink)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
            Text(label)
                .tkStyle(.caption)
                .foregroundStyle(Color.tk.inkMuted)
                .lineLimit(2)
            if let detail {
                Text(detail)
                    .tkStyle(.caption)
                    .foregroundStyle(Color.tk.inkMuted)
            }
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .padding(TK.Space.s3)
        .background(Color.tk.surfaceRaised, in: RoundedRectangle(cornerRadius: TK.Radius.md))
    }
}

private struct MostReadRowView: View {
    let item: RankedSeries

    var body: some View {
        HStack(spacing: TK.Space.s3) {
            Text(String(item.rank))
                .tkStyle(.body)
                .foregroundStyle(Color.tk.inkMuted)
                .frame(width: 20)
            MangaCover(seed: item.mangaId.description, url: item.coverUrl, headers: item.coverHeaders)
                .frame(width: 36)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: TK.Space.s1) {
                Text(item.title)
                    .tkStyle(.body)
                    .foregroundStyle(Color.tk.ink)
                    .lineLimit(1)
                Text(tr("activity_chapters", Int(item.count), table: .activity))
                    .tkStyle(.caption)
                    .foregroundStyle(Color.tk.inkMuted)
            }
            Spacer(minLength: TK.Space.s2)
            Text(change.text)
                .tkStyle(.caption)
                .foregroundStyle(change.color)
        }
        .contentShape(Rectangle())
    }

    /// The rank change in words; colour only reinforces it.
    private var change: (text: String, color: Color) {
        switch onEnum(of: item.change) {
        case .new: (tr("activity_rank_new", table: .activity), Color.tk.accent)
        case .same: (tr("activity_rank_same", table: .activity), Color.tk.inkMuted)
        case let .up(up): (tr("activity_rank_up", Int(up.places), table: .activity), Color.tk.success)
        case let .down(down): (tr("activity_rank_down", Int(down.places), table: .activity), Color.tk.inkMuted)
        }
    }
}

// MARK: - Helpers

private extension View {
    /// A list row that draws its own content edge to edge, with no cell background or separator.
    func plainRow() -> some View {
        listRowBackground(Color.clear)
            .listRowInsets(EdgeInsets())
            .listRowSeparator(.hidden)
    }
}

/// A local date (and time) from the ViewModel's plain components.
private func date(_ year: Int, _ month: Int, _ day: Int, hour: Int = 0, minute: Int = 0) -> Date {
    Calendar.current.date(from: DateComponents(year: year, month: month, day: day, hour: hour, minute: minute)) ?? .now
}
