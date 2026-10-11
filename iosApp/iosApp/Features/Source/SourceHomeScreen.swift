import Shared
import SwiftUI

/// A source's home: a hero carousel of its Popular listing, cover rails for its other listings and a
/// preview of its full catalog, under a large title and the system search field. Everything comes from
/// `SourceHomeViewModel`; "See all" and a submitted search push the paged catalog.
struct SourceHomeScreen: View {
    @Binding var path: [Route]
    @State private var model: ScreenModel<SourceHomeViewModel, SourceHomeState>

    private let sourceId: String

    init(sourceId: String, path: Binding<[Route]>) {
        self.sourceId = sourceId
        _path = path
        _model = State(initialValue: ScreenModel({ $0.sourceHomeViewModel(sourceId: sourceId) }, uiState: \.uiState))
    }

    var body: some View {
        // One ScrollView, title and search field from the first frame: swapping a spinner for the rails
        // remounted the scroll view under the large title and the search drawer, which made both jump.
        ScrollView {
            content
        }
        .background(Color.tk.canvas)
        .navigationTitle(loaded?.title ?? sourceId)
        .navigationSubtitle(subtitle)
        .navigationBarTitleDisplayMode(.large)
        .searchable(text: search, prompt: tr("source_search_hint", loaded?.title ?? sourceId, table: .source))
        .onSubmit(of: .search) { model.vm.onSearchSubmit() }
        .task { await model.observe() }
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
        case let .content(content):
            LazyVStack(alignment: .leading, spacing: TK.Space.s8) {
                // A loaded rail with nothing in it has nothing to show.
                ForEach(content.rails.filter { !($0.status == .loaded && $0.items.isEmpty) }, id: \.self) { rail in
                    section(rail)
                }
            }
            .padding(.vertical, TK.Space.s4)
        }
    }

    private var loaded: SourceHomeStateContent? {
        if case let .content(content) = onEnum(of: model.state) { return content }
        return nil
    }

    private var subtitle: String {
        guard let loaded else { return "" }
        return "\(loaded.lang) · \(tr("source_listing_count", Int(loaded.listingCount), table: .source))"
    }

    // MARK: Rails

    private func section(_ rail: SourceRail) -> some View {
        VStack(alignment: .leading, spacing: TK.Space.s3) {
            HStack(alignment: .firstTextBaseline) {
                Text(listingTitle(rail.listing))
                    .tkStyle(.titleL)
                    .foregroundStyle(Color.tk.ink)
                Spacer()
                if rail.status == .loaded {
                    Button(tr(rail.listing == nil ? "source_browse_all" : "source_see_all", table: .source)) {
                        model.vm.onSeeAll(listing: rail.listing)
                    }
                    .tint(Color.tk.accent)
                }
            }
            .padding(.horizontal, TK.Space.s5)

            switch rail.status {
            case .loading:
                ProgressView().frame(maxWidth: .infinity, minHeight: 120)
            case .failed:
                VStack(alignment: .leading, spacing: TK.Space.s2) {
                    Text(tr("source_rail_failed", table: .source))
                        .tkStyle(.body)
                        .foregroundStyle(Color.tk.inkMuted)
                    Button(tr("source_retry", table: .source)) { model.vm.onRetryRail(listing: rail.listing) }
                        .tint(Color.tk.accent)
                }
                .padding(.horizontal, TK.Space.s5)
            case .loaded:
                switch rail.listing {
                case .popular: heroRail(rail.items)
                case nil: catalogPreview(rail.items)
                default: coverRail(rail.items, showChapter: rail.listing == .latest)
                }
            }
        }
    }

    /// Popular: wide hero cards, snapping one at a time.
    private func heroRail(_ items: [CatalogItem]) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            LazyHStack(spacing: TK.Space.s3) {
                ForEach(items, id: \.key) { item in
                    Button { model.vm.onMangaClick(key: item.key) } label: {
                        MangaHeroCard(
                            seed: item.key,
                            title: item.title,
                            coverUrl: item.coverUrl,
                            coverHeaders: item.coverHeaders,
                            subtitle: item.subtitle
                        )
                        .frame(width: 300)
                    }
                    .buttonStyle(.plain)
                }
            }
            .scrollTargetLayout()
        }
        .contentMargins(.horizontal, TK.Space.s5, for: .scrollContent)
        .scrollTargetBehavior(.viewAligned)
    }

    /// Latest / New: covers, with the newest chapter and its age under each on Latest.
    private func coverRail(_ items: [CatalogItem], showChapter: Bool) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            LazyHStack(alignment: .top, spacing: TK.Space.s3) {
                ForEach(items, id: \.key) { item in
                    VStack(alignment: .leading, spacing: TK.Space.s1) {
                        cover(item)
                        if showChapter, let caption = chapterCaption(item) {
                            Text(caption)
                                .tkStyle(.caption)
                                .foregroundStyle(Color.tk.inkMuted)
                                .lineLimit(1)
                        }
                    }
                    .frame(width: 112)
                }
            }
        }
        .contentMargins(.horizontal, TK.Space.s5, for: .scrollContent)
    }

    /// The default catalog's first six, three across.
    private func catalogPreview(_ items: [CatalogItem]) -> some View {
        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: TK.Space.s2), count: 3), spacing: TK.Space.s2) {
            ForEach(items.prefix(6), id: \.key) { item in cover(item) }
        }
        .padding(.horizontal, TK.Space.s5)
    }

    private func cover(_ item: CatalogItem) -> some View {
        Button { model.vm.onMangaClick(key: item.key) } label: {
            CoverTile(
                seed: item.key,
                title: item.title,
                coverUrl: item.coverUrl,
                coverHeaders: item.coverHeaders,
                inLibraryLabel: item.inLibrary ? tr("source_in_library", table: .source) : nil,
                accessibilityLabel: item.title
            )
        }
        .buttonStyle(.plain)
    }

    private var search: Binding<String> {
        Binding(get: { loaded?.searchQuery ?? "" }, set: { model.vm.onSearchQueryChange(query: $0) })
    }
}
