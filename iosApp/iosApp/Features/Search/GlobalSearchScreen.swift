import Shared
import SwiftUI

/// Global search: library matches as you type, then one rail per installed source once the query is
/// submitted. In the Search tab the field sits in the Liquid Glass tab bar; pushed (from a recommendation)
/// it sits in the navigation bar, pre-filled and already searching. Everything comes from
/// `GlobalSearchViewModel`.
struct GlobalSearchScreen: View {
    @Binding var path: [Route]
    @State private var model: ScreenModel<GlobalSearchViewModel, GlobalSearchState>
    /// The search field's text buffer; every change is forwarded to the ViewModel, which owns the query.
    @State private var searchText: String
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    init(query: String?, path: Binding<[Route]>) {
        _path = path
        _model = State(initialValue: ScreenModel({ $0.globalSearchViewModel(query: query) }, uiState: \.uiState))
        _searchText = State(initialValue: query ?? "")
    }

    var body: some View {
        ScrollView {
            if model.state.showPrompt {
                ContentUnavailableView(
                    tr("search_title", table: .search),
                    systemImage: "magnifyingglass",
                    description: Text(tr("search_prompt", table: .search))
                )
                .padding(.top, TK.Space.s12)
            } else {
                results
            }
        }
        .background(Color.tk.canvas)
        .navigationTitle(tr("search_title", table: .search))
        .searchable(text: $searchText, prompt: tr("search_hint", table: .search))
        .onChange(of: searchText) { _, query in model.vm.onQueryChange(text: query) }
        .onSubmit(of: .search) { model.vm.onSubmit() }
        .task { await model.observe() }
        .navEvents(model.vm.navEvents, path: $path)
    }

    private var results: some View {
        LazyVStack(alignment: .leading, spacing: TK.Space.s8) {
            if !model.state.library.isEmpty {
                VStack(alignment: .leading, spacing: TK.Space.s3) {
                    header(tr("search_library_section", table: .search), subtitle: nil)
                    rail(model.state.library, id: \.mangaId) { hit in
                        Button { model.vm.onLibraryClick(mangaId: hit.mangaId) } label: {
                            CoverTile(
                                seed: hit.mangaId.description,
                                title: hit.title,
                                coverUrl: hit.coverUrl,
                                coverHeaders: hit.coverHeaders,
                                accessibilityLabel: hit.title
                            )
                        }
                        .buttonStyle(.plain)
                    }
                }
                .transition(.opacity)
            }
            ForEach(model.state.rails, id: \.sourceId) { source in
                section(source)
            }
        }
        .padding(.vertical, TK.Space.s4)
        .animation(reduceMotion ? TK.Motion.reduced : TK.Motion.enter, value: model.state)
    }

    private func section(_ source: SearchRail) -> some View {
        VStack(alignment: .leading, spacing: TK.Space.s3) {
            header(source.name, subtitle: source.lang, seeAll: source.status == .loaded ? source.sourceId : nil)
            switch source.status {
            case .loading:
                ProgressView().frame(maxWidth: .infinity, minHeight: 120)
            case .empty:
                note(tr("search_no_results", table: .search))
            case .failed:
                VStack(alignment: .leading, spacing: TK.Space.s2) {
                    note(tr("search_failed", table: .search))
                    Button(tr("search_retry", table: .search)) { model.vm.onRetry(sourceId: source.sourceId) }
                        .tint(Color.tk.accent)
                        .padding(.horizontal, TK.Space.s5)
                }
            case .loaded:
                rail(source.items, id: \.key) { item in
                    Button { model.vm.onResultClick(sourceId: source.sourceId, key: item.key) } label: {
                        CoverTile(
                            seed: item.key,
                            title: item.title,
                            coverUrl: item.coverUrl,
                            coverHeaders: item.coverHeaders,
                            inLibraryLabel: item.inLibrary ? tr("search_in_library", table: .search) : nil,
                            accessibilityLabel: item.title
                        )
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    private func header(_ title: String, subtitle: String?, seeAll sourceId: String? = nil) -> some View {
        HStack(alignment: .firstTextBaseline) {
            VStack(alignment: .leading, spacing: 0) {
                Text(title).tkStyle(.titleL).foregroundStyle(Color.tk.ink)
                if let subtitle {
                    Text(subtitle).tkStyle(.caption).foregroundStyle(Color.tk.inkMuted)
                }
            }
            Spacer()
            if let sourceId {
                Button(tr("search_see_all", table: .search)) { model.vm.onSeeAll(sourceId: sourceId) }
                    .tint(Color.tk.accent)
            }
        }
        .padding(.horizontal, TK.Space.s5)
    }

    private func note(_ text: String) -> some View {
        Text(text)
            .tkStyle(.body)
            .foregroundStyle(Color.tk.inkMuted)
            .padding(.horizontal, TK.Space.s5)
    }

    private func rail<Item, ID: Hashable, Cover: View>(
        _ items: [Item],
        id: KeyPath<Item, ID>,
        @ViewBuilder cover: @escaping (Item) -> Cover
    ) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            LazyHStack(alignment: .top, spacing: TK.Space.s3) {
                ForEach(items, id: id) { cover($0).frame(width: 112) }
            }
        }
        .contentMargins(.horizontal, TK.Space.s5, for: .scrollContent)
    }
}
