import NukeUI
import Shared
import SwiftUI

/// The system sheets the chrome opens: Chapters (with the series' bookmarks) and Reader settings.
struct ReaderSheets: View {
    let content: ReaderStateContent
    let vm: ReaderViewModel

    var body: some View {
        NavigationStack {
            switch content.sheet {
            case .chapters: ChaptersSheet(content: content, vm: vm)
            default: SettingsSheet(content: content, vm: vm)
            }
        }
        .presentationDetents([.medium, .large])
    }
}

// MARK: - Chapters / Bookmarks

private struct ChaptersSheet: View {
    let content: ReaderStateContent
    let vm: ReaderViewModel

    var body: some View {
        ScrollViewReader { proxy in
            List {
                Picker(selection: Binding(get: { content.chaptersTab }, set: { vm.onChaptersTabChange(tab: $0) })) {
                    Text(tr("reader_tab_chapters", table: .reader)).tag(ReaderChaptersTab.chapters)
                    Text(tr("reader_tab_bookmarks", table: .reader)).tag(ReaderChaptersTab.bookmarks)
                } label: {
                    EmptyView()
                }
                .pickerStyle(.segmented)
                .listRowBackground(Color.clear)
                .listRowInsets(EdgeInsets())
                .listRowSeparator(.hidden)

                switch content.chaptersTab {
                case .bookmarks: bookmarks
                default: chapters
                }
            }
            .onAppear {
                if let current = content.chapters.first(where: { $0.isCurrent }) { proxy.scrollTo(current.id, anchor: .center) }
            }
        }
        .navigationTitle(tr("reader_chapters", table: .reader))
        .navigationBarTitleDisplayMode(.inline)
    }

    private var chapters: some View {
        ForEach(content.chapters, id: \.id) { chapter in
            Button { vm.onChapterSelected(chapterId: chapter.id) } label: { ChapterRow(chapter: chapter) }
                .buttonStyle(.plain)
                .listRowBackground(chapter.isCurrent ? Color.tk.accentTint : Color.tk.surfaceRaised)
                .id(chapter.id)
        }
    }

    @ViewBuilder
    private var bookmarks: some View {
        if content.bookmarks.isEmpty {
            ContentUnavailableView(tr("reader_bookmarks_empty", table: .reader), systemImage: "bookmark")
                .listRowBackground(Color.clear)
        }
        ForEach(content.bookmarks, id: \.id) { bookmark in
            Button { vm.onBookmarkSelected(bookmarkId: bookmark.id) } label: { BookmarkRow(bookmark: bookmark) }
                .buttonStyle(.plain)
                .listRowBackground(Color.tk.surfaceRaised)
        }
    }
}

private struct ChapterRow: View {
    let chapter: ReaderChapterItem

    var body: some View {
        HStack(spacing: TK.Space.s3) {
            VStack(alignment: .leading, spacing: TK.Space.s0_5) {
                Text(chapter.name ?? tr("reader_chapter_number", chapter.numberLabel ?? "", table: .reader))
                    .tkStyle(.body)
                    .foregroundStyle(chapter.isRead && !chapter.isCurrent ? Color.tk.inkFaint : Color.tk.ink)
                    .lineLimit(1)
                if let meta {
                    Text(meta).tkStyle(.caption).foregroundStyle(Color.tk.inkMuted).lineLimit(1)
                }
            }
            Spacer(minLength: 0)
            if chapter.bookmarkCount > 0 {
                HStack(spacing: TK.Space.s0_5) {
                    Image(systemName: "bookmark.fill").imageScale(.small).foregroundStyle(Color.tk.accent)
                    if chapter.bookmarkCount > 1 {
                        Text(verbatim: "\(chapter.bookmarkCount)").tkStyle(.caption).foregroundStyle(Color.tk.inkMuted)
                    }
                }
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(tr("reader_chapter_bookmarks", Int(chapter.bookmarkCount), table: .reader))
            }
            if chapter.isDownloaded {
                Image(systemName: "arrow.down.circle.fill")
                    .symbolRenderingMode(.hierarchical)
                    .foregroundStyle(Color.tk.inkMuted)
                    .accessibilityLabel(tr("reader_chapter_downloaded", table: .reader))
            }
        }
        .contentShape(Rectangle())
        .accessibilityAddTraits(chapter.isCurrent ? .isSelected : [])
    }

    private var meta: String? {
        let parts = [chapter.isCurrent ? tr("reader_chapter_current", table: .reader) : nil,
                     chapter.isRead && !chapter.isCurrent ? tr("reader_chapter_read", table: .reader) : nil,
                     chapter.scanlator].compactMap { $0 }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }
}

private struct BookmarkRow: View {
    let bookmark: ReaderBookmarkItem

    var body: some View {
        HStack(spacing: TK.Space.s3) {
            // The page itself, small, through the page pipeline; an expired source URL leaves the ground.
            LazyImage(request: PageRequest.make(url: bookmark.imageUrl, headers: bookmark.imageHeaders, localPath: bookmark.localPath, width: 120)) { state in
                if let image = state.image {
                    image.resizable().scaledToFill()
                } else {
                    Color.tk.surface
                }
            }
            .pipeline(.tanukiPages)
            .frame(width: 36, height: 50)
            .clipShape(RoundedRectangle(cornerRadius: TK.Radius.xs, style: .continuous))
            .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: TK.Space.s0_5) {
                Text(bookmark.chapterName ?? tr("reader_chapter_number", bookmark.chapterNumberLabel ?? "", table: .reader))
                    .tkStyle(.body)
                    .foregroundStyle(Color.tk.ink)
                    .lineLimit(1)
                Text(tr("reader_bookmark_page", Int(bookmark.page) + 1, table: .reader))
                    .tkStyle(.caption)
                    .foregroundStyle(Color.tk.inkMuted)
            }
            Spacer(minLength: 0)
        }
        .contentShape(Rectangle())
    }
}

// MARK: - Settings

private struct SettingsSheet: View {
    let content: ReaderStateContent
    let vm: ReaderViewModel

    var body: some View {
        List {
            Section {
                Picker(selection: Binding(get: { content.readingMode }, set: { vm.onReadingModeChange(mode: $0) })) {
                    Text(tr("reader_mode_ltr", table: .reader)).tag(ReadingMode.leftToRight)
                    Text(tr("reader_mode_rtl", table: .reader)).tag(ReadingMode.rightToLeft)
                    Text(tr("reader_mode_vertical", table: .reader)).tag(ReadingMode.vertical)
                    Text(tr("reader_mode_webtoon", table: .reader)).tag(ReadingMode.webtoon)
                } label: {
                    EmptyView()
                }
                .pickerStyle(.inline)
            } header: {
                Text(tr("reader_mode_title", table: .reader))
            } footer: {
                Text(tr("reader_mode_hint", table: .reader))
            }
            .listRowBackground(Color.tk.surfaceRaised)

            Section {
                HStack(spacing: TK.Space.s6) {
                    swatch(.black, "reader_background_black")
                    swatch(.paper, "reader_background_paper")
                    swatch(.sepia, "reader_background_sepia")
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, TK.Space.s2)
            } header: {
                Text(tr("reader_background_title", table: .reader))
            }
            .listRowBackground(Color.tk.surfaceRaised)
        }
        .navigationTitle(tr("reader_settings", table: .reader))
        .navigationBarTitleDisplayMode(.inline)
    }

    /// A round swatch of the ground; the picked one ringed in accent with a check in the ground's ink.
    private func swatch(_ background: ReaderBackground, _ key: String) -> some View {
        let selected = content.background == background
        return Button { vm.onBackgroundChange(background: background) } label: {
            VStack(spacing: TK.Space.s1) {
                Circle()
                    .fill(ReaderGround.color(background))
                    .overlay(Circle().strokeBorder(selected ? Color.tk.accent : Color.tk.line, lineWidth: selected ? 2 : 1))
                    .overlay {
                        if selected { Image(systemName: "checkmark").foregroundStyle(ReaderGround.ink(background)) }
                    }
                    .frame(width: 44, height: 44)
                Text(tr(key, table: .reader)).tkStyle(.caption).foregroundStyle(Color.tk.ink)
            }
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}
