import Shared
import SwiftUI

/// A series: one scrolling page (hero, actions, synopsis, info rows, chapters) with a floating
/// "Read Ch. N" button. All state — the open sheet and chapter selection included — is
/// `MangaViewModel`'s; this file draws it and forwards taps.
struct MangaScreen: View {
    @Binding var path: [Route]
    @State private var model: ScreenModel<MangaViewModel, MangaState>
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
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
                MangaHero(
                    header: content.header,
                    seed: seed,
                    line: heroLineText(content.heroLine),
                    onChooseCover: { model.vm.onOpenCoverPicker() }
                )
                if !selecting { actions(content) }
                MangaSynopsis(text: content.header.synopsis) { model.vm.onOpenSheet(sheet: .about) }
                if !content.tagRow.visible.isEmpty {
                    TagRowView(row: content.tagRow) { model.vm.onOpenSheet(sheet: .about) }
                }
                // Placement is the ViewModel's call: above the chapters only while the series isn't in the
                // library (still being decided on); otherwise suggestions live in About.
                if content.recommendationsPlacement == .aboveChapters, let series = content.series {
                    RecommendationsRail(recommendations: series.recommendations) { model.vm.onRecommendationClick(title: $0.title) }
                }
                chapters(content)
            }
            .padding(.bottom, TK.Space.s16)
            // Enrichment landing changes the title, cover, synopsis and tags: fade into them.
            .animation(reduceMotion ? TK.Motion.reduced : TK.Motion.enter, value: content.metadata)
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
        .sheet(isPresented: sheetBinding(content, .about)) { aboutSheet(content) }
        .sheet(isPresented: sheetBinding(content, .coverPicker)) { coverPickerSheet(content) }
        .sheet(isPresented: sheetBinding(content, .matchSearch)) { MatchSearchSheet(editor: content.editor, vm: model.vm) }
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
            Button(tr("manga_refresh_dismiss", table: .manga), role: .cancel) { model.vm.onDismissRefreshError() }
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
                .contentTransition(.symbolEffect(.replace))
                .frame(maxWidth: .infinity)
            }
            Button {
                model.vm.onOpenSheet(sheet: .categories)
            } label: {
                Label(tr("manga_action_categories", table: .manga), systemImage: "folder").frame(maxWidth: .infinity)
            }
        }
        // Content, not navigation: solid capsules on `surface-raised`, never glass (glass is the chrome layer).
        .buttonStyle(.borderedProminent)
        .buttonBorderShape(.capsule)
        .tint(Color.tk.surfaceRaised)
        .foregroundStyle(Color.tk.ink)
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
            Button { model.vm.onOpenSheet(sheet: .readingMode) } label: { // replaces About as the open sheet
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
        .frame(minHeight: TK.Size.tapIos)
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
                if content.canFilterChapters {
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
            // Sorting, filtering and newly synced chapters move rows rather than swap the list.
            LazyVStack(spacing: 0) {
                ForEach(content.rows, id: \.id) { row in
                    VStack(spacing: 0) {
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
                    .transition(.opacity)
                }
            }
            .animation(reduceMotion ? TK.Motion.reduced : TK.Motion.enter, value: content.rows.map(\.id))
        }
    }

    // MARK: Bottom

    /// The one primary action: glass-prominent, tinted accent.
    private func readButton(_ content: MangaStateContent) -> some View {
        Button { model.vm.onReadClick() } label: {
            Label(
                content.resumeNumberLabel.map { tr("manga_read_chapter_short", $0, table: .manga) }
                    ?? tr("manga_read_action", table: .manga),
                systemImage: "play.fill"
            )
            .tkStyle(.headline)
            .padding(.horizontal, TK.Space.s4)
        }
        // A floating capsule sized to its label, not a full-width bar; `.safeAreaInset` (on the page) keeps
        // it from covering the last rows.
        .buttonStyle(.glassProminent)
        .tint(Color.tk.accent)
        .controlSize(.large)
        .padding(.bottom, TK.Space.s2)
    }

    private func selectionBar(_ content: MangaStateContent) -> some View {
        let selection = content.selection
        // Neighbouring glass buttons share one container, so they merge instead of overlapping.
        return GlassEffectContainer { HStack {
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
        } }
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
                Menu {
                    if content.isMatched {
                        Toggle(tr("manga_metadata_use_source", table: .manga), isOn: Binding(
                            get: { content.useSourceInfo },
                            set: { model.vm.onUseSourceInfoChange(useSourceInfo: $0) }
                        ))
                    }
                    Button(tr("manga_metadata_find", table: .manga), systemImage: "magnifyingglass") { model.vm.onOpenMatchSearch() }
                    Button(tr("manga_metadata_cover_title", table: .manga), systemImage: "photo.on.rectangle") { model.vm.onOpenCoverPicker() }
                    if content.isMatched {
                        Button(tr("manga_metadata_unlink", table: .manga), systemImage: "link.badge.plus", role: .destructive) {
                            model.vm.onUnlinkMetadata()
                        }
                    }
                    if let url = URL(string: content.header.url) {
                        Divider()
                        ShareLink(item: url)
                    }
                } label: {
                    Image(systemName: "ellipsis")
                }
                .accessibilityLabel(tr("manga_metadata_more", table: .manga))
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
                if content.canFilterBookmarked {
                    Section {
                        Toggle(isOn: Binding(get: { content.bookmarkedOnly }, set: { _ in model.vm.onToggleBookmarkedOnly() })) {
                            Label(tr("manga_filter_bookmarked", table: .manga), systemImage: "bookmark")
                        }
                        .tint(Color.tk.accent)
                    } header: {
                        Text(tr("manga_filter_section_bookmarks", table: .manga))
                    }
                }
                if content.canFilterScanlators {
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

    /// Reference material, off the main page: full synopsis, every tag, the rating, characters, reviews,
    /// suggestions (for a library series) and the Source / Reading mode rows.
    private func aboutSheet(_ content: MangaStateContent) -> some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: TK.Space.s6) {
                    if let synopsis = content.header.synopsis {
                        Text(synopsis)
                            .tkStyle(.bodyLarge)
                            .foregroundStyle(Color.tk.ink)
                            .padding(.horizontal, TK.Space.s5)
                    }
                    if let rating = content.heroLine.rating {
                        // A String, not a literal: an interpolated literal is a LocalizedStringKey, and Xcode
                        // would extract it into the generated catalog.
                        Label(tr("manga_about_rating", table: .manga) + " " + rating, systemImage: "star.fill")
                            .tkStyle(.label)
                            .foregroundStyle(Color.tk.accent)
                            .padding(.horizontal, TK.Space.s5)
                    }
                    AboutTags(tags: content.tags)
                    if let series = content.series {
                        MetadataSections(
                            series: series,
                            showRecommendations: content.recommendationsPlacement == .aboutOnly,
                            onRecommendation: { model.vm.onRecommendationClick(title: $0.title) }
                        )
                    }
                    infoRows(content)
                }
                .padding(.vertical, TK.Space.s4)
            }
            .navigationTitle(tr("manga_about_title", table: .manga))
            .navigationBarTitleDisplayMode(.inline)
        }
        .presentationDetents([.medium, .large])
    }

    private func coverPickerSheet(_ content: MangaStateContent) -> some View {
        NavigationStack {
            ScrollView {
                if let options = content.editor.coverOptions {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 100), spacing: TK.Space.s3)], spacing: TK.Space.s3) {
                        ForEach(options, id: \.url) { option in
                            Button { model.vm.onPickCover(url: option.url) } label: {
                                VStack(spacing: TK.Space.s1) {
                                    MangaCover(seed: seed, url: option.url)
                                        .overlay {
                                            if option.url == content.header.coverUrl {
                                                RoundedRectangle(cornerRadius: TK.Radius.sm, style: .continuous)
                                                    .stroke(Color.tk.accent, lineWidth: 3)
                                            }
                                        }
                                    Text(coverLabel(option))
                                        .tkStyle(.caption)
                                        .foregroundStyle(Color.tk.inkMuted)
                                }
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(TK.Space.s5)
                } else {
                    ProgressView().padding(.top, TK.Space.s12)
                }
            }
            .navigationTitle(tr("manga_metadata_cover_title", table: .manga))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button(tr("manga_metadata_cover_default", table: .manga)) { model.vm.onPickCover(url: nil) }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    private func coverLabel(_ option: CoverOption) -> String {
        switch option.origin {
        case .mangaDex: option.label.map { tr("manga_metadata_cover_volume", $0, table: .manga) } ?? "MangaDex"
        case .aniList: "AniList"
        case .source: tr("manga_info_source", table: .manga)
        }
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

/// The hero: cover, title, author and ONE metadata line ("Ongoing · 143 ch. · ★ 8.2"). The source and
/// the AniList match live elsewhere (About, the ⋯ menu).
private struct MangaHero: View {
    let header: MangaHeader
    let seed: String
    let line: String
    let onChooseCover: () -> Void

    var body: some View {
        HStack(alignment: .bottom, spacing: TK.Space.s4) {
            MangaCover(seed: seed, url: header.coverUrl, headers: header.coverHeaders)
                .frame(width: 124)
                .shadow(color: Color.tk.shadowCover, radius: 4, y: 2) // shadow-cover: 0 2px 8px, the hero cover only
                .onLongPressGesture(perform: onChooseCover)
                .accessibilityAction(named: tr("manga_metadata_cover_title", table: .manga), onChooseCover)
            VStack(alignment: .leading, spacing: TK.Space.s1) {
                Text(header.title)
                    .tkStyle(.hero)
                    .foregroundStyle(Color.tk.ink)
                    .lineLimit(4)
                if let author = header.author {
                    Text(author).tkStyle(.body).foregroundStyle(Color.tk.inkMuted).lineLimit(2)
                }
                Text(line)
                    .tkStyle(.caption)
                    .foregroundStyle(Color.tk.inkMuted)
                    .lineLimit(1)
            }
            .padding(.bottom, TK.Space.s1)
        }
        .padding(.horizontal, TK.Space.s5)
        .padding(.top, TK.Space.s4)
    }
}

/// The synopsis, clamped to three lines. "Show more" opens the About sheet, where the full text lives.
private struct MangaSynopsis: View {
    let text: String?
    let onMore: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: TK.Space.s1) {
            Text(text ?? tr("manga_info_no_description", table: .manga))
                .tkStyle(.bodyLarge)
                .foregroundStyle(text == nil ? Color.tk.inkMuted : Color.tk.ink)
                .lineLimit(3)
            if text != nil {
                Button(tr("manga_info_show_more", table: .manga), action: onMore)
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
            if !selecting {
                if row.bookmarkCount > 0 { bookmarkMark }
                downloadControl
            }
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

    /// The chapter has bookmarked pages: a small filled bookmark (and the count past one), read as words.
    private var bookmarkMark: some View {
        HStack(spacing: TK.Space.s0_5) {
            Image(systemName: "bookmark.fill")
                .imageScale(.small)
                .foregroundStyle(Color.tk.accent)
            if row.bookmarkCount > 1 {
                Text(verbatim: "\(row.bookmarkCount)")
                    .tkStyle(.caption)
                    .foregroundStyle(Color.tk.inkMuted)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(tr("manga_chapter_bookmarks", Int(row.bookmarkCount), table: .manga))
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
            .frame(width: TK.Size.tapIos, height: TK.Size.tapIos)
            .accessibilityLabel(tr("manga_download_downloaded", table: .manga))
        case .downloading:
            Button(action: onDownload) {
                ProgressView(value: Double(row.downloadProgress?.floatValue ?? 0))
                    .progressViewStyle(.circular)
                    .tint(Color.tk.accent)
            }
            .frame(width: TK.Size.tapIos, height: TK.Size.tapIos)
            .accessibilityLabel(tr("manga_download_cancel", table: .manga))
        case .queued:
            Button(action: onDownload) { Image(systemName: "clock").foregroundStyle(Color.tk.inkMuted) }
                .frame(width: TK.Size.tapIos, height: TK.Size.tapIos)
                .accessibilityLabel(tr("manga_download_queued", table: .manga))
        case .failed:
            Button(action: onDownload) { Image(systemName: "exclamationmark.circle").foregroundStyle(Color.tk.danger) }
                .frame(width: TK.Size.tapIos, height: TK.Size.tapIos)
                .accessibilityLabel(tr("manga_download_failed", table: .manga))
        case nil:
            Button(action: onDownload) { Image(systemName: "arrow.down.circle").foregroundStyle(Color.tk.inkMuted) }
                .frame(width: TK.Size.tapIos, height: TK.Size.tapIos)
                .accessibilityLabel(tr("manga_download_chapter", table: .manga))
        }
    }
}

/// A display-only genre/tag chip (solid; the page is content).
private struct TagChip: View {
    let text: String
    var blurred = false

    var body: some View {
        Text(text)
            .tkStyle(.label)
            .foregroundStyle(Color.tk.ink)
            .blur(radius: blurred ? 5 : 0)
            .padding(.horizontal, TK.Space.s3)
            .frame(height: 32)
            .background(Color.tk.surfaceRaised, in: Capsule())
    }
}

/// The page's one chip row: a few tags and a "+N" chip; tapping it opens About.
private struct TagRowView: View {
    let row: TagRow
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: TK.Space.s2) {
                ForEach(row.visible, id: \.self) { TagChip(text: $0) }
                if row.overflow > 0 {
                    TagChip(text: tr("manga_tags_more", Int(row.overflow), table: .manga))
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .buttonStyle(.plain)
        .padding(.horizontal, TK.Space.s5)
        .accessibilityLabel(tr("manga_about_title", table: .manga))
    }
}

/// Every tag, spoilers blurred until asked.
private struct AboutTags: View {
    let tags: [TagItem]
    @State private var showSpoilers = false

    var body: some View {
        if !tags.isEmpty {
            VStack(alignment: .leading, spacing: TK.Space.s3) {
                Text(tr("manga_metadata_tags", table: .manga))
                    .tkStyle(.titleL)
                    .foregroundStyle(Color.tk.ink)
                FlowLayout {
                    ForEach(tags, id: \.name) { tag in
                        TagChip(text: tag.name, blurred: tag.spoiler && !showSpoilers)
                    }
                }
                if tags.contains(where: \.spoiler) && !showSpoilers {
                    Button(tr("manga_metadata_show_spoilers", table: .manga)) { showSpoilers = true }
                        .tkStyle(.label)
                        .tint(Color.tk.accent)
                }
            }
            .padding(.horizontal, TK.Space.s5)
        }
    }
}

/// "You might also like": a cover rail; a tap opens the title on AniList (a source search once global
/// search lands).
private struct RecommendationsRail: View {
    let recommendations: [MetadataRecommendation]
    /// Looks the series up in the installed sources (global search).
    let onSelect: (MetadataRecommendation) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: TK.Space.s3) {
            Text(tr("manga_metadata_recommendations", table: .manga))
                .tkStyle(.titleL)
                .foregroundStyle(Color.tk.ink)
                .padding(.horizontal, TK.Space.s5)
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(alignment: .top, spacing: TK.Space.s3) {
                    ForEach(recommendations, id: \.anilistId) { recommendation in
                        Button { onSelect(recommendation) } label: {
                            CoverTile(
                                seed: "\(recommendation.anilistId)",
                                title: recommendation.title,
                                coverUrl: recommendation.coverUrl,
                                accessibilityLabel: recommendation.title
                            )
                            .frame(width: 112)
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            .contentMargins(.horizontal, TK.Space.s5, for: .scrollContent)
        }
    }
}

/// What the metadata adds below the info rows: tags, characters, reviews and recommendations. Rendered
/// only once enrichment has landed, so the page above never waits for it.
private struct MetadataSections: View {
    let series: SeriesMetadata
    let showRecommendations: Bool
    let onRecommendation: (MetadataRecommendation) -> Void
    @Environment(\.openURL) private var openURL

    var body: some View {
        VStack(alignment: .leading, spacing: TK.Space.s6) {
            if !series.characters.isEmpty { characters }
            if !series.reviews.isEmpty { reviews }
            if showRecommendations && !series.recommendations.isEmpty {
                RecommendationsRail(recommendations: series.recommendations, onSelect: onRecommendation)
            }
        }
    }

    private var characters: some View {
        section(tr("manga_metadata_characters", table: .manga)) {
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(alignment: .top, spacing: TK.Space.s3) {
                    ForEach(series.characters, id: \.name) { character in
                        VStack(spacing: TK.Space.s1) {
                            TanukiImage(url: character.imageUrl) { CoverSeed.ground(character.name) }
                                .frame(width: 72, height: 72)
                                .clipShape(Circle())
                            Text(character.name)
                                .tkStyle(.caption)
                                .foregroundStyle(Color.tk.ink)
                                .lineLimit(2)
                                .multilineTextAlignment(.center)
                        }
                        .frame(width: 80)
                    }
                }
            }
            .contentMargins(.horizontal, TK.Space.s5, for: .scrollContent)
        }
    }

    private var reviews: some View {
        section(tr("manga_metadata_reviews", table: .manga)) {
            VStack(spacing: TK.Space.s2) {
                ForEach(series.reviews, id: \.url) { review in
                    Button { if let url = URL(string: review.url) { openURL(url) } } label: {
                        HStack(alignment: .top, spacing: TK.Space.s3) {
                            Text(review.summary)
                                .tkStyle(.body)
                                .foregroundStyle(Color.tk.ink)
                                .multilineTextAlignment(.leading)
                            Spacer(minLength: 0)
                            if let score = review.score {
                                Text(verbatim: "\(score.intValue)")
                                    .tkStyle(.numeric)
                                    .foregroundStyle(Color.tk.accent)
                            }
                        }
                        .padding(TK.Space.s4)
                        .background(Color.tk.surfaceRaised, in: RoundedRectangle(cornerRadius: TK.Radius.md, style: .continuous))
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, TK.Space.s5)
        }
    }

    private func section(_ title: String, @ViewBuilder content: () -> some View) -> some View {
        VStack(alignment: .leading, spacing: TK.Space.s3) {
            Text(title)
                .tkStyle(.titleL)
                .foregroundStyle(Color.tk.ink)
                .padding(.horizontal, TK.Space.s5)
            content()
        }
    }
}

/// Manual matching: search AniList by title and confirm the right series.
private struct MatchSearchSheet: View {
    let editor: MetadataEditor
    let vm: MangaViewModel
    @State private var query = ""

    var body: some View {
        NavigationStack {
            List {
                if let results = editor.matchResults {
                    if results.isEmpty {
                        Text(tr("manga_metadata_search_empty", table: .manga)).foregroundStyle(Color.tk.inkMuted)
                    }
                    ForEach(results, id: \.anilistId) { result in
                        Button { vm.onConfirmMatch(anilistId: result.anilistId) } label: {
                            HStack(spacing: TK.Space.s3) {
                                MangaCover(seed: "\(result.anilistId)", url: result.coverUrl, radius: TK.Radius.xs)
                                    .frame(width: 44)
                                VStack(alignment: .leading, spacing: TK.Space.s0_5) {
                                    Text(result.title).tkStyle(.body).foregroundStyle(Color.tk.ink)
                                    Text([result.format, result.year.map { "\($0.intValue)" }].compactMap { $0 }.joined(separator: " · "))
                                        .tkStyle(.caption)
                                        .foregroundStyle(Color.tk.inkMuted)
                                }
                            }
                        }
                    }
                } else {
                    ProgressView().frame(maxWidth: .infinity)
                }
            }
            .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always))
            .onSubmit(of: .search) { vm.onMatchSearch(query: query) }
            .onAppear { query = editor.matchQuery }
            .navigationTitle(tr("manga_metadata_find", table: .manga))
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}

// MARK: - Copy

/// "Ongoing · 143 ch. · ★ 8.2"; absent pieces are left out.
private func heroLineText(_ line: HeroLine) -> String {
    [
        line.status.flatMap(publishStatusTitle),
        tr("manga_chapter_count_short", Int(line.chapterCount), table: .manga),
        line.rating.map { "★ \($0)" }
    ].compactMap { $0 }.joined(separator: " · ")
}

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
