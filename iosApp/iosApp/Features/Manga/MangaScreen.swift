import Shared
import SwiftUI

/// A series: one scrolling page (hero, actions, synopsis, info rows, chapters) with a floating
/// "Read Ch. N" button. All state — the open sheet and chapter selection included — is
/// `MangaViewModel`'s; this file draws it and forwards taps.
struct MangaScreen: View {
    @Binding var path: [Route]
    @State private var model: ScreenModel<MangaViewModel, MangaState>
    private let seed: String

    init(mangaId: KotlinUuid, path: Binding<[Route]>) {
        _path = path
        seed = "\(mangaId)"
        _model = State(initialValue: ScreenModel({ $0.mangaViewModel(mangaId: mangaId) }, uiState: \.uiState))
    }

    var body: some View {
        content
            .background(Color.tk.canvas)
            .task { await model.observe() }
            .navEvents(model.vm.navEvents, path: $path)
    }

    @ViewBuilder
    private var content: some View {
        switch onEnum(of: model.state) {
        case .loading:
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        case .notFound:
            ContentUnavailableView(tr("manga_not_found", table: .manga), systemImage: "book.closed")
        case let .content(content):
            page(content)
        }
    }

    private func page(_ content: MangaStateContent) -> some View {
        let selecting = content.selection.isActive
        return ScrollView {
            VStack(alignment: .leading, spacing: TK.Space.s6) {
                MangaHero(header: content.header, seed: seed)
                if !selecting { actions(content) }
                MangaSynopsis(text: content.header.synopsis)
                infoRows(content)
                chapters(content)
            }
            .padding(.bottom, TK.Space.s16)
        }
        .background(alignment: .top) {
            // A wash of the cover's ground colour behind the hero, fading into the page.
            LinearGradient(colors: [CoverSeed.ground(seed).opacity(0.35), Color.tk.canvas], startPoint: .top, endPoint: .bottom)
                .frame(height: 380)
                .ignoresSafeArea(edges: .top)
        }
        .refreshable { model.vm.onRefresh() }
        .navigationTitle(selecting ? tr("manga_selection_count", Int(content.selection.count), table: .manga) : "")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { toolbar(content) }
        .safeAreaInset(edge: .bottom) {
            if selecting {
                selectionBar(content)
            } else if !content.chapters.isEmpty {
                readButton(content)
            }
        }
        .sheet(isPresented: sheetBinding(content, .categories)) { categoriesSheet(content) }
        .sheet(isPresented: sheetBinding(content, .chapterFilter)) { filterSheet(content) }
        .sheet(isPresented: sheetBinding(content, .readingMode)) { readingModeSheet(content) }
        .alert(
            tr("manga_download_delete_all_title", table: .manga),
            isPresented: sheetBinding(content, .confirmDeleteDownloads)
        ) {
            Button(tr("manga_download_delete_all_confirm", table: .manga), role: .destructive) { model.vm.onDeleteAllDownloads() }
            Button(tr("manga_download_delete_all_cancel", table: .manga), role: .cancel) { model.vm.onDismissSheet() }
        } message: {
            Text(tr("manga_download_delete_all_message", table: .manga))
        }
        .alert(
            tr("manga_refresh_failed", table: .manga),
            isPresented: Binding(get: { content.refresh.failed }, set: { _ in })
        ) {
            Button(tr("manga_refresh_retry", table: .manga)) { model.vm.onRefresh() }
            Button("OK", role: .cancel) { model.vm.onDismissRefreshError() }
        }
    }

    // MARK: Actions

    /// Glass secondary actions: reading status (a menu that also holds library membership) and categories.
    private func actions(_ content: MangaStateContent) -> some View {
        let header = content.header
        return HStack(spacing: TK.Space.s2) {
            Menu {
                if !header.inLibrary {
                    Button(tr("manga_info_add_to_library", table: .manga), systemImage: "plus") { model.vm.onAddToLibrary() }
                }
                Section(tr("manga_reading_status_set", table: .manga)) {
                    ForEach([ReadingStatus.reading, .completed, .onHold, .dropped, .planningToRead], id: \.self) { status in
                        Button {
                            model.vm.onSetReadingStatus(status: status)
                        } label: {
                            if header.readingStatus == status {
                                Label(readingStatusTitle(status), systemImage: "checkmark")
                            } else {
                                Text(readingStatusTitle(status))
                            }
                        }
                    }
                }
                if header.inLibrary {
                    Button(tr("manga_info_remove_from_library", table: .manga), systemImage: "minus.circle", role: .destructive) {
                        model.vm.onRemoveFromLibrary()
                    }
                }
            } label: {
                Label(
                    header.readingStatus.map(readingStatusTitle) ?? tr("manga_info_add_to_library", table: .manga),
                    systemImage: header.inLibrary ? "bookmark.fill" : "bookmark"
                )
                .frame(maxWidth: .infinity)
            }
            Button {
                model.vm.onOpenSheet(sheet: .categories)
            } label: {
                Label(tr("manga_action_categories", table: .manga), systemImage: "folder").frame(maxWidth: .infinity)
            }
        }
        .buttonStyle(.glass)
        .controlSize(.large)
        .padding(.horizontal, TK.Space.s5)
    }

    private func infoRows(_ content: MangaStateContent) -> some View {
        let header = content.header
        return VStack(spacing: 0) {
            infoRow(
                tr("manga_info_source", table: .manga),
                value: [header.sourceName ?? tr("manga_info_source_unknown", table: .manga), header.sourceLang?.uppercased()]
                    .compactMap { $0 }.joined(separator: " · ")
            )
            Divider().padding(.leading, TK.Space.s4)
            Button { model.vm.onOpenSheet(sheet: .readingMode) } label: {
                infoRow(tr("manga_reading_mode_title", table: .manga), value: readingModeTitle(header.readingMode), chevron: true)
            }
            .buttonStyle(.plain)
        }
        .background(Color.tk.surfaceRaised, in: RoundedRectangle(cornerRadius: TK.Radius.lg, style: .continuous))
        .padding(.horizontal, TK.Space.s5)
    }

    private func infoRow(_ title: String, value: String, chevron: Bool = false) -> some View {
        HStack(spacing: TK.Space.s3) {
            Text(title).tkStyle(.body).foregroundStyle(Color.tk.ink)
            Spacer()
            Text(value).tkStyle(.body).foregroundStyle(Color.tk.inkMuted)
            if chevron {
                Image(systemName: "chevron.forward").font(.footnote.weight(.semibold)).foregroundStyle(Color.tk.inkFaint)
            }
        }
        .padding(.horizontal, TK.Space.s4)
        .frame(minHeight: 48)
        .contentShape(Rectangle())
    }

    // MARK: Chapters

    private func chapters(_ content: MangaStateContent) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text(tr("manga_chapter_count", Int(content.chapters.count), table: .manga))
                    .tkStyle(.titleL)
                    .foregroundStyle(Color.tk.ink)
                Spacer()
                if content.canFilterScanlators {
                    Button { model.vm.onOpenSheet(sheet: .chapterFilter) } label: {
                        Image(systemName: content.isChapterFilterActive
                            ? "line.3.horizontal.decrease.circle.fill" : "line.3.horizontal.decrease")
                    }
                    .accessibilityLabel(tr("manga_chapters_filter", table: .manga))
                }
                Button { model.vm.onToggleChapterSort() } label: {
                    Image(systemName: "arrow.up.arrow.down")
                }
                .accessibilityLabel(tr("manga_chapters_sort", table: .manga))
            }
            .tint(Color.tk.ink)
            .padding(.horizontal, TK.Space.s5)
            .padding(.bottom, TK.Space.s1)

            if content.rows.isEmpty {
                Text(tr("manga_no_chapters", table: .manga))
                    .tkStyle(.body)
                    .foregroundStyle(Color.tk.inkMuted)
                    .padding(TK.Space.s5)
            }
            LazyVStack(spacing: 0) {
                ForEach(content.rows, id: \.id) { row in
                    ChapterRowView(
                        row: row,
                        selecting: content.selection.isActive,
                        onTap: { model.vm.onChapterClick(chapterId: row.id) },
                        onLongPress: { model.vm.onChapterLongClick(chapterId: row.id) },
                        onDownload: { model.vm.onChapterDownloadClick(chapterId: row.id) },
                        onDeleteDownload: { model.vm.onDeleteChapterDownload(chapterId: row.id) }
                    )
                    Divider().padding(.leading, TK.Space.s5)
                }
            }
        }
    }

    // MARK: Bottom

    /// The one primary action: glass-prominent, tinted accent.
    private func readButton(_ content: MangaStateContent) -> some View {
        Button { model.vm.onReadClick() } label: {
            Label(
                content.resumeNumberLabel.map { tr("manga_read_chapter", $0, table: .manga) }
                    ?? tr("manga_read_action", table: .manga),
                systemImage: "play.fill"
            )
            .tkStyle(.headline)
            .frame(maxWidth: .infinity, minHeight: 36)
        }
        .buttonStyle(.glassProminent)
        .tint(Color.tk.accent)
        .controlSize(.large)
        .padding(.horizontal, TK.Space.s5)
        .padding(.bottom, TK.Space.s2)
    }

    private func selectionBar(_ content: MangaStateContent) -> some View {
        let selection = content.selection
        return HStack {
            Button(tr("manga_selection_mark_read", table: .manga), systemImage: "checkmark.circle") {
                model.vm.onMarkSelectionRead(read: true)
            }
            .disabled(!selection.canMarkRead)
            Button(tr("manga_selection_mark_unread", table: .manga), systemImage: "circle") {
                model.vm.onMarkSelectionRead(read: false)
            }
            .disabled(!selection.canMarkUnread)
            Button(tr("manga_selection_download", table: .manga), systemImage: "arrow.down.circle") {
                model.vm.onDownloadSelection()
            }
            .disabled(!selection.canDownload)
            Button(tr("manga_selection_delete_download", table: .manga), systemImage: "trash", role: .destructive) {
                model.vm.onDeleteSelectionDownloads()
            }
            .disabled(!selection.canDeleteDownload)
        }
        .labelStyle(.iconOnly)
        .buttonStyle(.glass)
        .controlSize(.large)
        .padding(.bottom, TK.Space.s2)
    }

    @ToolbarContentBuilder
    private func toolbar(_ content: MangaStateContent) -> some ToolbarContent {
        if content.selection.isActive {
            ToolbarItem(placement: .topBarLeading) {
                Button(tr("manga_selection_exit", table: .manga), systemImage: "xmark") { model.vm.onClearSelection() }
            }
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Button(tr(content.selection.allSelected ? "manga_selection_deselect_all" : "manga_selection_select_all", table: .manga)) {
                        model.vm.onToggleSelectAll()
                    }
                    Button(tr("manga_selection_select_above", table: .manga)) { model.vm.onSelectAbove() }
                        .disabled(!content.selection.canSelectAbove)
                    Button(tr("manga_selection_select_below", table: .manga)) { model.vm.onSelectBelow() }
                        .disabled(!content.selection.canSelectBelow)
                } label: {
                    Image(systemName: "checklist")
                }
            }
        } else {
            ToolbarItemGroup(placement: .topBarTrailing) {
                Menu {
                    Button(tr("manga_download_next", 1, table: .manga)) { model.vm.onDownloadNextChapters(count: 1) }
                    Button(tr("manga_download_next", 5, table: .manga)) { model.vm.onDownloadNextChapters(count: 5) }
                    Button(tr("manga_download_next", 10, table: .manga)) { model.vm.onDownloadNextChapters(count: 10) }
                    Button(tr("manga_download_all_unread", table: .manga)) { model.vm.onDownloadNextChapters(count: nil) }
                    Button(tr("manga_download_all", table: .manga)) { model.vm.onDownloadAllChapters() }
                    Divider()
                    Button(tr("manga_download_queue", table: .manga), systemImage: "list.bullet") { model.vm.onOpenDownloadQueue() }
                    if content.hasDownloads {
                        Button(tr("manga_download_delete_all", table: .manga), systemImage: "trash", role: .destructive) {
                            model.vm.onOpenSheet(sheet: .confirmDeleteDownloads)
                        }
                    }
                } label: {
                    Image(systemName: "arrow.down.circle")
                }
                .accessibilityLabel(tr("manga_download_menu", table: .manga))
                if let url = URL(string: content.header.url) {
                    ShareLink(item: url)
                }
            }
        }
    }

    // MARK: Sheets

    private func sheetBinding(_ content: MangaStateContent, _ sheet: MangaSheet) -> Binding<Bool> {
        // State-driven: dismissing tells the ViewModel, which closes it.
        Binding(get: { content.sheet == sheet }, set: { if !$0 { model.vm.onDismissSheet() } })
    }

    private func categoriesSheet(_ content: MangaStateContent) -> some View {
        NavigationStack {
            ScrollView {
                if content.categories.isEmpty {
                    Text(tr("manga_categories_empty", table: .manga))
                        .tkStyle(.body)
                        .foregroundStyle(Color.tk.inkMuted)
                        .padding(TK.Space.s5)
                } else {
                    FlowLayout {
                        ForEach(content.categories, id: \.self) { chip in
                            FilterChip(title: chip.name, selected: chip.isMember) {
                                model.vm.onToggleCategory(categoryId: chip.category.id)
                            }
                        }
                    }
                    .padding(TK.Space.s5)
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            .navigationTitle(tr("manga_categories_title", table: .manga))
            .navigationBarTitleDisplayMode(.inline)
        }
        .presentationDetents([.medium])
    }

    private func filterSheet(_ content: MangaStateContent) -> some View {
        NavigationStack {
            List {
                Section {
                    ForEach(content.scanlators, id: \.name) { group in
                        Toggle(isOn: Binding(get: { group.isShown }, set: { _ in model.vm.onToggleScanlator(name: group.name) })) {
                            VStack(alignment: .leading) {
                                Text(group.name).tkStyle(.body)
                                Text(tr("manga_chapter_count", Int(group.chapterCount), table: .manga))
                                    .tkStyle(.caption)
                                    .foregroundStyle(Color.tk.inkMuted)
                            }
                        }
                        .tint(Color.tk.accent)
                    }
                } header: {
                    Text(tr("manga_filter_section_scanlators", table: .manga))
                } footer: {
                    Text(tr("manga_filter_scanlators_help", table: .manga))
                }
            }
            .navigationTitle(tr("manga_filter_title", table: .manga))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button(tr("manga_sheet_reset", table: .manga)) { model.vm.onResetChapterFilter() }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    private func readingModeSheet(_ content: MangaStateContent) -> some View {
        NavigationStack {
            List {
                ForEach([ReadingMode.rightToLeft, .leftToRight, .webtoon, .vertical, .continuousVertical], id: \.self) { mode in
                    Button { model.vm.onSetReadingMode(mode: mode) } label: {
                        HStack {
                            Text(readingModeTitle(mode)).tkStyle(.body).foregroundStyle(Color.tk.ink)
                            Spacer()
                            if content.header.readingMode == mode {
                                Image(systemName: "checkmark").foregroundStyle(Color.tk.accent)
                            }
                        }
                    }
                }
            }
            .navigationTitle(tr("manga_reading_mode_title", table: .manga))
            .navigationBarTitleDisplayMode(.inline)
        }
        .presentationDetents([.medium])
    }
}

// MARK: - Pieces

/// The hero: cover, title, author and a status line ("Ongoing · 143 chapters · MangaFire").
private struct MangaHero: View {
    let header: MangaHeader
    let seed: String

    var body: some View {
        HStack(alignment: .bottom, spacing: TK.Space.s4) {
            MangaCover(seed: seed, url: header.coverUrl, headers: header.coverHeaders)
                .frame(width: 124)
                .shadow(color: .black.opacity(0.18), radius: 8, y: 2)
            VStack(alignment: .leading, spacing: TK.Space.s1) {
                Text(header.title)
                    .tkStyle(.titleXL)
                    .foregroundStyle(Color.tk.ink)
                    .lineLimit(4)
                if let author = header.author {
                    Text(author).tkStyle(.body).foregroundStyle(Color.tk.inkMuted).lineLimit(2)
                }
                Label(statusLine, systemImage: "clock")
                    .tkStyle(.caption)
                    .foregroundStyle(Color.tk.inkMuted)
            }
            .padding(.bottom, TK.Space.s1)
        }
        .padding(.horizontal, TK.Space.s5)
        .padding(.top, TK.Space.s4)
    }

    private var statusLine: String {
        [
            publishStatusTitle(header.publishStatus),
            tr("manga_chapter_count", Int(header.chapterCount), table: .manga),
            header.sourceName
        ].compactMap { $0 }.joined(separator: " · ")
    }
}

/// The synopsis, clamped to three lines with a "More"/"Less" toggle. The expansion is view chrome, not
/// screen state, so it stays here.
private struct MangaSynopsis: View {
    let text: String?
    @State private var expanded = false

    var body: some View {
        VStack(alignment: .leading, spacing: TK.Space.s1) {
            Text(text ?? tr("manga_info_no_description", table: .manga))
                .tkStyle(.bodyLarge)
                .foregroundStyle(text == nil ? Color.tk.inkMuted : Color.tk.ink)
                .lineLimit(expanded ? nil : 3)
            if text != nil {
                Button(tr(expanded ? "manga_info_show_less" : "manga_info_show_more", table: .manga)) {
                    withAnimation(TK.Motion.enter) { expanded.toggle() }
                }
                .tkStyle(.label)
                .tint(Color.tk.accent)
            }
        }
        .padding(.horizontal, TK.Space.s5)
    }
}

/// One chapter: unread dot, title, date · group (or "Page N" when started), and its download control.
private struct ChapterRowView: View {
    let row: ChapterRow
    let selecting: Bool
    let onTap: () -> Void
    let onLongPress: () -> Void
    let onDownload: () -> Void
    let onDeleteDownload: () -> Void

    var body: some View {
        HStack(spacing: TK.Space.s3) {
            if selecting {
                Image(systemName: row.isSelected ? "checkmark.circle.fill" : "circle")
                    .foregroundStyle(row.isSelected ? Color.tk.accent : Color.tk.inkFaint)
            } else if !row.isRead {
                Circle().fill(Color.tk.accent).frame(width: 8, height: 8)
                    .accessibilityHidden(true)
            }
            VStack(alignment: .leading, spacing: TK.Space.s0_5) {
                Text(title)
                    .tkStyle(.body)
                    .foregroundStyle(row.isRead ? Color.tk.inkFaint : Color.tk.ink)
                    .lineLimit(1)
                if let meta {
                    Text(meta)
                        .tkStyle(.caption)
                        .foregroundStyle(row.isRead ? Color.tk.inkFaint : Color.tk.inkMuted)
                        .lineLimit(1)
                }
            }
            Spacer(minLength: 0)
            if !selecting { downloadControl }
        }
        .padding(.horizontal, TK.Space.s5)
        .frame(minHeight: 64)
        .contentShape(Rectangle())
        .onTapGesture(perform: onTap)
        .onLongPressGesture(perform: onLongPress)
    }

    private var title: String {
        if let number = row.numberLabel {
            let numbered = tr("manga_chapter_number", number, table: .manga)
            if let name = row.name, !name.isEmpty { return "\(numbered) · \(name)" }
            return numbered
        }
        return row.name ?? tr("manga_chapter_untitled", table: .manga)
    }

    private var meta: String? {
        let date = row.dateUploaded.map {
            Date(timeIntervalSince1970: Double($0.toEpochMilliseconds()) / 1000).formatted(date: .abbreviated, time: .omitted)
        }
        let page = row.lastPageRead.map { tr("manga_chapter_page", Int($0), table: .manga) }
        let parts = [page ?? date, row.scanlator].compactMap { $0 }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }

    @ViewBuilder
    private var downloadControl: some View {
        switch row.download {
        case .downloaded:
            Menu {
                Button(tr("manga_download_delete", table: .manga), systemImage: "trash", role: .destructive, action: onDeleteDownload)
            } label: {
                Image(systemName: "checkmark.circle.fill").foregroundStyle(Color.tk.accent)
            }
            .frame(width: 44, height: 44)
            .accessibilityLabel(tr("manga_download_downloaded", table: .manga))
        case .downloading:
            Button(action: onDownload) {
                ProgressView(value: Double(row.downloadProgress?.floatValue ?? 0))
                    .progressViewStyle(.circular)
                    .tint(Color.tk.accent)
            }
            .frame(width: 44, height: 44)
            .accessibilityLabel(tr("manga_download_cancel", table: .manga))
        case .queued:
            Button(action: onDownload) { Image(systemName: "clock").foregroundStyle(Color.tk.inkMuted) }
                .frame(width: 44, height: 44)
                .accessibilityLabel(tr("manga_download_queued", table: .manga))
        case .failed:
            Button(action: onDownload) { Image(systemName: "exclamationmark.circle").foregroundStyle(Color.tk.danger) }
                .frame(width: 44, height: 44)
                .accessibilityLabel(tr("manga_download_failed", table: .manga))
        case nil:
            Button(action: onDownload) { Image(systemName: "arrow.down.circle").foregroundStyle(Color.tk.inkMuted) }
                .frame(width: 44, height: 44)
                .accessibilityLabel(tr("manga_download_chapter", table: .manga))
        }
    }
}

// MARK: - Copy

private func readingStatusTitle(_ status: ReadingStatus) -> String {
    switch status {
    case .reading: tr("manga_reading_status_reading", table: .manga)
    case .completed: tr("manga_reading_status_completed", table: .manga)
    case .onHold: tr("manga_reading_status_on_hold", table: .manga)
    case .dropped: tr("manga_reading_status_dropped", table: .manga)
    case .planningToRead: tr("manga_reading_status_planning", table: .manga)
    }
}

private func readingModeTitle(_ mode: ReadingMode) -> String {
    switch mode {
    case .leftToRight: tr("manga_reading_mode_left_to_right", table: .manga)
    case .rightToLeft: tr("manga_reading_mode_right_to_left", table: .manga)
    case .webtoon: tr("manga_reading_mode_webtoon", table: .manga)
    case .vertical: tr("manga_reading_mode_vertical", table: .manga)
    case .continuousVertical: tr("manga_reading_mode_continuous_vertical", table: .manga)
    }
}

/// `nil` for an unknown status: the hero line just leaves it out.
private func publishStatusTitle(_ status: PublishStatus) -> String? {
    switch status {
    case .ongoing: tr("manga_status_ongoing", table: .manga)
    case .completed: tr("manga_status_completed", table: .manga)
    case .licensed: tr("manga_status_licensed", table: .manga)
    case .publishingFinished: tr("manga_status_publishing_finished", table: .manga)
    case .cancelled: tr("manga_status_cancelled", table: .manga)
    case .hiatus: tr("manga_status_hiatus", table: .manga)
    case .unknown: nil
    }
}
