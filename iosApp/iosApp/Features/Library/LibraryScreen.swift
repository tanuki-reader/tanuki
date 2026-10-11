import Shared
import SwiftUI

/// The Library tab: a large title, system search, and a grid of the user's series. The sort & filter
/// sheet, the search text and every filter live in `LibraryViewModel` (the filter and sort persist across
/// launches); this file only draws them and forwards taps.
struct LibraryScreen: View {
    @Binding var path: [Route]
    @State private var model = ScreenModel({ $0.libraryViewModel() }, uiState: \.uiState)

    var body: some View {
        content
            .background(Color.tk.canvas)
            .navigationTitle(tr("library_title", table: .library))
            .searchable(text: search, prompt: tr("library_search_hint", table: .library))
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { model.vm.onFilterClick() } label: {
                        Image(systemName: filterActive
                            ? "line.3.horizontal.decrease.circle.fill"
                            : "line.3.horizontal.decrease")
                    }
                    .accessibilityLabel(tr("library_sheet_title", table: .library))
                }
            }
            .sheet(isPresented: sheetPresented) {
                if case let .content(content) = onEnum(of: model.state) {
                    LibrarySheet(content: content, vm: model.vm)
                }
            }
            .task { await model.observe() }
            .navEvents(model.vm.navEvents, path: $path)
    }

    @ViewBuilder
    private var content: some View {
        switch onEnum(of: model.state) {
        case .loading:
            ProgressView()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        case let .content(content):
            if let empty = content.empty {
                emptyView(empty, query: content.searchQuery)
            } else {
                ScrollView {
                    LazyVGrid(
                        columns: [GridItem(.adaptive(minimum: content.gridSize.minCoverWidth), spacing: TK.Space.s2)],
                        spacing: TK.Space.s2
                    ) {
                        ForEach(content.items, id: \.mangaId) { item in
                            Button { model.vm.onMangaClick(mangaId: item.mangaId) } label: {
                                LibraryCoverTile(item: item)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.horizontal, TK.Space.s5)
                    .padding(.vertical, TK.Space.s4)
                }
            }
        }
    }

    @ViewBuilder
    private func emptyView(_ empty: LibraryEmpty, query: String) -> some View {
        switch empty {
        case .library:
            ContentUnavailableView {
                Label(tr("library_empty_title", table: .library), systemImage: "books.vertical")
            } description: {
                Text(tr("library_empty_message", table: .library))
            }
        case .search:
            ContentUnavailableView.search(text: query)
        case .filter:
            ContentUnavailableView {
                Label(tr("library_filter_empty", table: .library), systemImage: "line.3.horizontal.decrease")
            } actions: {
                Button(tr("library_sheet_reset", table: .library)) { model.vm.onResetFilters() }
            }
        }
    }

    private var filterActive: Bool {
        if case let .content(content) = onEnum(of: model.state) { return content.filterActive }
        return false
    }

    private var search: Binding<String> {
        Binding(
            get: {
                if case let .content(content) = onEnum(of: model.state) { return content.searchQuery }
                return ""
            },
            set: { model.vm.onSearchQueryChange(query: $0) }
        )
    }

    /// State-driven only: dismissing tells the ViewModel, which closes it.
    private var sheetPresented: Binding<Bool> {
        Binding(
            get: {
                if case let .content(content) = onEnum(of: model.state) { return content.sheetOpen }
                return false
            },
            set: { if !$0 { model.vm.toLibraryState() } }
        )
    }
}

extension LibraryGridSize {
    /// The smallest a cover may get; the grid fits as many columns as this allows. Mirrors Android's
    /// `LibraryGrid` (90/114/150dp).
    var minCoverWidth: CGFloat {
        switch self {
        case .small: 90
        case .medium: 114
        case .large: 150
        }
    }
}
