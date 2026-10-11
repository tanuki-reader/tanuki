import Shared
import SwiftUI

/// Library → ⋯ → Bookmarks: every page bookmark, grouped by series, newest first. A row opens the reader
/// on that page; swipe to delete. Everything comes from `BookmarksViewModel`.
struct BookmarksScreen: View {
    @Binding var path: [Route]
    @State private var model = ScreenModel({ $0.bookmarksViewModel() }, uiState: \.uiState)

    var body: some View {
        content
            .background(Color.tk.canvas)
            .navigationTitle(tr("bookmarks_title", table: .bookmarks))
            .navigationBarTitleDisplayMode(.inline)
            .task { await model.observe() }
            .navEvents(model.vm.navEvents, path: $path)
    }

    @ViewBuilder
    private var content: some View {
        switch onEnum(of: model.state) {
        case .loading:
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        case let .content(content):
            if content.series.isEmpty {
                ContentUnavailableView(tr("bookmarks_empty", table: .bookmarks), systemImage: "bookmark")
            } else {
                List {
                    ForEach(content.series, id: \.mangaId) { series in
                        Section {
                            ForEach(series.bookmarks, id: \.id) { row in
                                Button { model.vm.onOpen(row: row) } label: { BookmarkRowView(row: row) }
                                    .buttonStyle(.plain)
                                    .listRowBackground(Color.tk.surfaceRaised)
                                    .swipeActions(edge: .trailing) {
                                        Button(role: .destructive) { model.vm.onDelete(id: row.id) } label: {
                                            Label(tr("bookmarks_delete", table: .bookmarks), systemImage: "trash")
                                        }
                                    }
                            }
                        } header: {
                            SeriesHeader(series: series)
                        }
                    }
                }
                .scrollContentBackground(.hidden)
            }
        }
    }
}

private struct SeriesHeader: View {
    let series: BookmarkSeries

    var body: some View {
        HStack(spacing: TK.Space.s3) {
            MangaCover(seed: series.mangaId.description, url: series.coverUrl, headers: series.coverHeaders, radius: TK.Radius.xs)
                .frame(width: 32)
                .accessibilityHidden(true)
            Text(series.title)
                .tkStyle(.headline)
                .foregroundStyle(Color.tk.ink)
                .lineLimit(2)
                .textCase(nil)
        }
        .padding(.vertical, TK.Space.s1)
    }
}

private struct BookmarkRowView: View {
    let row: BookmarkRow

    var body: some View {
        HStack(spacing: TK.Space.s3) {
            // The page itself, through TanukiImage (Kotlin fetch, Cloudflare-aware); an expired source URL
            // leaves the seeded ground.
            MangaCover(seed: row.chapterId.description, url: row.imageUrl ?? row.localPath.map { "file://\($0)" }, headers: row.imageHeaders, radius: TK.Radius.xs)
                .frame(width: 36)
                .accessibilityHidden(true)
            Text(tr("bookmarks_row", chapter, Int(row.page) + 1, table: .bookmarks))
                .tkStyle(.body)
                .foregroundStyle(Color.tk.ink)
                .lineLimit(1)
            Spacer(minLength: 0)
        }
        .contentShape(Rectangle())
    }

    private var chapter: String {
        if let number = row.chapterNumberLabel { return tr("bookmarks_chapter_number", number, table: .bookmarks) }
        return row.chapterName ?? tr("bookmarks_chapter_untitled", table: .bookmarks)
    }
}
