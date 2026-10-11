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
    private let title: String

    init(sourceId: String, listing: String?, query: String?, path: Binding<[Route]>) {
        _path = path
        _model = State(initialValue: ScreenModel(
            { $0.sourceCatalogViewModel(sourceId: sourceId, listing: listing, query: query) },
            uiState: \.uiState
        ))
        _searchText = State(initialValue: query ?? "")
        _sourceName = State(initialValue: sourceId)
        title = listing.flatMap { SourceListing.companion.fromWireName(wireName: $0) }.map(listingTitle) ?? sourceId
    }

    var body: some View {
        content
            .background(Color.tk.canvas)
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .searchable(text: $searchText, prompt: tr("source_search_hint", sourceName, table: .source))
            .onChange(of: searchText) { _, query in model.vm.onQueryChange(text: query) }
            .task { await model.observe() }
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
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        case .error:
            ContentUnavailableView {
                Label(tr("source_load_error", table: .source), systemImage: "exclamationmark.triangle")
            } actions: {
                Button(tr("source_retry", table: .source)) { model.vm.onRetry() }
            }
        case let .results(results):
            if results.items.isEmpty && results.append != .loading {
                switch onEnum(of: results) {
                case let .searching(search): ContentUnavailableView.search(text: search.query)
                case .browsing: ContentUnavailableView(tr("source_results_empty", table: .source), systemImage: "books.vertical")
                }
            } else {
                grid(results)
            }
        }
    }

    private func grid(_ results: SourceBrowseStateResults) -> some View {
        ScrollView {
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 114), spacing: TK.Space.s2)], spacing: TK.Space.s2) {
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
