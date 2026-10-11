import Shared
import SwiftUI

/// One source's full catalog — a listing's "See all", or a search — as an infinitely scrolling grid.
/// Paging, dedupe and the search debounce are `SourceBrowseViewModel`'s; this view draws its state and
/// asks for the next page as the grid's end comes on screen.
struct SourceCatalogScreen: View {
    @Binding var path: [Route]
    @State private var model: ScreenModel<SourceBrowseViewModel, SourceBrowseState>
    /// The search field's text buffer; every change is forwarded to the ViewModel, which owns the query.
    @State private var searchText: String
    /// The source's name once its metadata resolves (the ViewModel's `chrome`), for the search prompt:
    /// a search covers the whole source, not the listing in the title.
    @State private var sourceName: String
    /// The cover size chosen in Appearance (the ViewModel's `gridSize`), shared with the library grid.
    @State private var gridSize: LibraryGridSize = .medium
    private let title: String?

    init(sourceId: String, listing: String?, query: String?, path: Binding<[Route]>) {
        _path = path
        _model = State(initialValue: ScreenModel(
            { $0.sourceCatalogViewModel(sourceId: sourceId, listing: listing, query: query) },
            uiState: \.uiState
        ))
        _searchText = State(initialValue: query ?? "")
        _sourceName = State(initialValue: sourceId)
        // A search's results are titled by the source (set once its name loads); a listing by its label,
        // and no listing is the default catalog, "All series".
        title = query != nil ? nil : listingTitle(listing.flatMap { SourceListing.companion.fromWireName(wireName: $0) })
    }

    var body: some View {
        // One ScrollView for every state: the search drawer attaches to it once. Swapping a spinner for a
        // grid swapped the scroll view underneath `.searchable`, which collapsed and re-showed the field.
        ScrollView {
            content
        }
        .background(Color.tk.canvas)
        .navigationTitle(title ?? sourceName)
        .navigationBarTitleDisplayMode(.inline)
        .searchable(
            text: $searchText,
            placement: .navigationBarDrawer(displayMode: .always),
            prompt: tr("source_search_hint", sourceName, table: .source)
        )
        .onChange(of: searchText) { _, query in model.vm.onQueryChange(text: query) }
        .task { await model.observe() }
        .task {
            for await size in model.vm.gridSize { gridSize = size }
        }
        .task {
            for await chrome in model.vm.chrome {
                if let name = chrome.title { sourceName = name }
            }
        }
        .navEvents(model.vm.navEvents, path: $path)
    }

    @ViewBuilder
    private var content: some View {
        switch onEnum(of: model.state) {
        case .loading:
            ProgressView().frame(maxWidth: .infinity).padding(.top, TK.Space.s16)
        case .error:
            ContentUnavailableView {
                Label(tr("source_load_error", table: .source), systemImage: "exclamationmark.triangle")
            } actions: {
                Button(tr("source_retry", table: .source)) { model.vm.onRetry() }
            }
            .padding(.top, TK.Space.s12)
        case let .results(results):
            if results.items.isEmpty && results.append != .loading {
                Group {
                    switch onEnum(of: results) {
                    case let .searching(search): ContentUnavailableView.search(text: search.query)
                    case .browsing: ContentUnavailableView(tr("source_results_empty", table: .source), systemImage: "books.vertical")
                    }
                }
                .padding(.top, TK.Space.s12)
            } else {
                grid(results)
            }
        }
    }

    @ViewBuilder
    private func grid(_ results: SourceBrowseStateResults) -> some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: CGFloat(gridSize.minCoverWidth)), spacing: TK.Space.s2)], spacing: TK.Space.s2) {
            ForEach(results.tiles, id: \.key) { item in
                Button { model.vm.onItemClick(key: item.key) } label: {
                    CoverTile(
                        seed: item.key,
                        title: item.title,
                        coverUrl: item.coverUrl,
                        coverHeaders: item.coverHeaders,
                        accessibilityLabel: item.title
                    )
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, TK.Space.s5)
        .padding(.vertical, TK.Space.s4)

        footer(results.append)
    }

    /// The grid's end: reaching it asks for the next page; a failed page offers a retry.
    @ViewBuilder
    private func footer(_ append: Append) -> some View {
        switch append {
        case .idle:
            Color.clear.frame(height: 1).onAppear { model.vm.onLoadMore() }
        case .loading:
            ProgressView().padding(TK.Space.s4)
        case .error:
            Button(tr("source_retry", table: .source)) { model.vm.onLoadMore() }
                .tint(Color.tk.accent)
                .padding(TK.Space.s4)
        case .end:
            EmptyView()
        }
    }
}
