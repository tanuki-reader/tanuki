import Shared
import SwiftUI

/// The download queue, grouped by series. Every control is one `DownloadsViewModel` call, and none edits
/// the list: the queue's own flow moves the rows. The only local state is the "cancel all" confirmation,
/// a presentation decision (as on Android).
struct DownloadsScreen: View {
    @Binding var path: [Route]
    @State private var model = ScreenModel({ $0.downloadsViewModel() }, uiState: \.uiState)
    @State private var confirmingCancelAll = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let state = model.state
        content(state)
            .background(Color.tk.canvas)
            .navigationTitle(tr("downloads_title", table: .downloads))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                if !state.isEmpty {
                    ToolbarItemGroup(placement: .topBarTrailing) {
                        Button { model.vm.onTogglePaused() } label: {
                            Image(systemName: state.paused ? "play.fill" : "pause.fill")
                        }
                        .accessibilityLabel(tr(state.paused ? "downloads_resume" : "downloads_pause", table: .downloads))
                        Button { confirmingCancelAll = true } label: { Image(systemName: "trash") }
                            .accessibilityLabel(tr("downloads_cancel_all", table: .downloads))
                            .disabled(state.busy)
                    }
                }
            }
            .alert(tr("downloads_cancel_all_title", table: .downloads), isPresented: $confirmingCancelAll) {
                Button(tr("downloads_cancel_all_dismiss", table: .downloads), role: .cancel) {}
                Button(tr("downloads_cancel_all_confirm", table: .downloads), role: .destructive) {
                    model.vm.onCancelAll()
                }
            } message: {
                Text(tr("downloads_cancel_all_message", table: .downloads))
            }
            .task { await model.observe() }
            .navEvents(model.vm.navEvents, path: $path)
    }

    @ViewBuilder
    private func content(_ state: DownloadsState) -> some View {
        if state.loading {
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if state.isEmpty {
            ContentUnavailableView(tr("downloads_empty", table: .downloads), systemImage: "arrow.down.circle")
        } else {
            List {
                Section {
                    StatusLine(state: state)
                    if !state.runsInBackground {
                        Label(tr("downloads_foreground_only", table: .downloads), systemImage: "arrow.down.circle")
                            .tkStyle(.caption)
                            .foregroundStyle(Color.tk.inkMuted)
                    }
                }
                .listRowBackground(Color.clear)
                .listRowSeparator(.hidden)

                ForEach(state.series, id: \.mangaId) { series in
                    Section {
                        SeriesHeader(series: series, vm: model.vm)
                        ForEach(series.chapters, id: \.chapterId) { chapter in
                            ChapterRow(chapter: chapter, vm: model.vm)
                                .swipeActions(edge: .trailing) {
                                    Button(role: .destructive) { model.vm.onCancelChapter(chapterId: chapter.chapterId) } label: {
                                        Label(tr("downloads_cancel_chapter", table: .downloads), systemImage: "xmark")
                                    }
                                }
                        }
                    }
                    .listRowBackground(Color.tk.surfaceRaised)
                }
            }
            .scrollContentBackground(.hidden)
            // Dimmed, not disabled: the rows leaving one by one are the feedback that a sweep is working.
            .opacity(state.busy ? 0.5 : 1)
            .animation(reduceMotion ? TK.Motion.reduced : TK.Motion.enter, value: state.series)
        }
    }
}

/// How much is queued, or why nothing moves. A hold carries a symbol and words, never colour alone.
private struct StatusLine: View {
    let state: DownloadsState

    var body: some View {
        switch state.hold {
        case .paused: hold("pause.circle", "downloads_paused_banner")
        case .waitingForWifi: hold("wifi.exclamationmark", "downloads_waiting_for_wifi")
        default:
            Text(tr("downloads_queue_count", Int(state.chapterCount), table: .downloads))
                .tkStyle(.caption)
                .foregroundStyle(Color.tk.inkMuted)
        }
    }

    private func hold(_ symbol: String, _ key: String) -> some View {
        Label {
            Text(tr(key, table: .downloads)).foregroundStyle(Color.tk.ink)
        } icon: {
            Image(systemName: symbol).foregroundStyle(Color.tk.accent)
        }
        .tkStyle(.caption)
    }
}

/// Cover, title and count (or failures); tapping opens the series, the trash clears its chapters.
private struct SeriesHeader: View {
    let series: DownloadSeriesItem
    let vm: DownloadsViewModel

    var body: some View {
        HStack(spacing: TK.Space.s3) {
            Button { vm.onOpenManga(mangaId: series.mangaId) } label: {
                HStack(spacing: TK.Space.s3) {
                    MangaCover(seed: series.mangaId.description, url: series.coverUrl, headers: series.coverHeaders, radius: TK.Radius.xs)
                        .frame(width: 40)
                        .accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: TK.Space.s1) {
                        Text(series.title)
                            .tkStyle(.headline)
                            .foregroundStyle(Color.tk.ink)
                            .lineLimit(2)
                        Text(count)
                            .tkStyle(.caption)
                            .foregroundStyle(series.failedCount > 0 ? Color.tk.danger : Color.tk.inkMuted)
                    }
                    Spacer(minLength: 0)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            Button { vm.onCancelSeries(mangaId: series.mangaId) } label: {
                Image(systemName: "trash").foregroundStyle(Color.tk.inkMuted)
            }
            .buttonStyle(.borderless)
            .accessibilityLabel(tr("downloads_cancel_series", table: .downloads))
        }
    }

    private var count: String {
        series.failedCount > 0
            ? tr("downloads_series_failed", Int(series.failedCount), table: .downloads)
            : tr("downloads_series_count", series.chapters.count, table: .downloads)
    }
}

/// Leading progress (or an error glyph), title over status, then Try again / Verify and cancel.
private struct ChapterRow: View {
    let chapter: DownloadChapterItem
    let vm: DownloadsViewModel

    var body: some View {
        HStack(spacing: TK.Space.s3) {
            leading.frame(width: TK.Size.iconSm, height: TK.Size.iconSm)
            VStack(alignment: .leading, spacing: TK.Space.s1) {
                Text(title).tkStyle(.body).foregroundStyle(Color.tk.ink).lineLimit(1)
                Text(status)
                    .tkStyle(.caption)
                    .foregroundStyle(chapter.failed ? Color.tk.danger : Color.tk.inkMuted)
                    .lineLimit(1)
            }
            Spacer(minLength: TK.Space.s2)
            if chapter.failed {
                Button(tr(chapter.error == .needsVerification ? "downloads_verify_chapter" : "downloads_retry_chapter", table: .downloads)) {
                    vm.onRetryChapter(chapterId: chapter.chapterId)
                }
                .buttonStyle(.bordered)
                .controlSize(.small)
            }
            Button { vm.onCancelChapter(chapterId: chapter.chapterId) } label: {
                Image(systemName: "xmark").foregroundStyle(Color.tk.inkMuted)
            }
            .buttonStyle(.borderless)
            .accessibilityLabel(tr("downloads_cancel_chapter", table: .downloads))
        }
    }

    @ViewBuilder
    private var leading: some View {
        if chapter.failed {
            Image(systemName: "exclamationmark.circle.fill").foregroundStyle(Color.tk.danger)
        } else if let progress = chapter.progress {
            ProgressView(value: Double(progress.floatValue)).progressViewStyle(.circular).tint(Color.tk.accent)
        } else {
            ProgressView().controlSize(.small)
        }
    }

    /// The chapter list's rule: the number, else the source's name, else a placeholder.
    private var title: String {
        if let number = chapter.numberLabel { return tr("downloads_chapter_number", number, table: .downloads) }
        return chapter.name ?? tr("downloads_chapter_untitled", table: .downloads)
    }

    private var status: String {
        if chapter.failed {
            return tr(errorKey, table: .downloads)
        }
        guard let total = chapter.pageCount else { return tr("downloads_state_queued", table: .downloads) }
        return tr("downloads_state_pages", Int(chapter.downloadedPages), Int(total.intValue), table: .downloads)
    }

    private var errorKey: String {
        switch chapter.error {
        case .network: "downloads_error_network"
        case .sourceUnavailable: "downloads_error_source"
        case .needsVerification: "downloads_error_verification"
        case .outOfSpace: "downloads_error_space"
        default: "downloads_error_unknown"
        }
    }
}
