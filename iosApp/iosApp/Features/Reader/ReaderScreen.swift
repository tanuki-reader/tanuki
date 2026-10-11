import Nuke
import Shared
import SwiftUI

/// The reader. Everything it shows comes from `ReaderViewModel`: the item list (pages of a sliding window
/// of chapters with transition slides between them), the chrome, the sheets. Swift lays the items out,
/// reports which item is in focus **by key**, and applies the ViewModel's scroll commands, also by key —
/// the contract that survives the list being re-indexed when a neighbouring chapter loads or leaves.
///
/// The layouts are SwiftUI scroll views (a strip for webtoons, a paging scroll for the paged modes),
/// chosen by measurement over a `UICollectionView`: both kept the visible page through a 60-page prepend
/// and through late height changes above it, and SwiftUI scrolled 200 pages with no dropped frames
/// (docs/swiftui-migration.md, Phase 8 C).
struct ReaderScreen: View {
    @Binding var path: [Route]
    @State private var model: ScreenModel<ReaderViewModel, ReaderState>

    init(mangaId: KotlinUuid, chapterId: KotlinUuid, page: KotlinInt?, path: Binding<[Route]>) {
        _path = path
        _model = State(initialValue: ScreenModel({ $0.readerViewModel(mangaId: mangaId, chapterId: chapterId, page: page) }, uiState: \.uiState))
    }

    var body: some View {
        let content = model.state as? ReaderStateContent
        ZStack {
            ReaderGround(background: content?.background).ignoresSafeArea()
            switch onEnum(of: model.state) {
            case .loading:
                ProgressView().tint(ReaderGround.ink(content?.background))
            case .error:
                VStack(spacing: TK.Space.s4) {
                    Text(tr("reader_error_message", table: .reader))
                        .tkStyle(.body)
                        .foregroundStyle(ReaderGround.ink(nil))
                    Button(tr("reader_retry", table: .reader)) { model.vm.onRetry() }
                        .buttonStyle(.glassProminent)
                        .tint(Color.tk.accent)
                }
            case let .content(content):
                ReaderContentView(content: content, vm: model.vm, latest: { model.state as? ReaderStateContent })
            }
        }
        .toolbar(.hidden, for: .navigationBar)
        .statusBarHidden(!(content?.chromeVisible ?? true))
        .persistentSystemOverlays(content?.chromeVisible == false ? .hidden : .automatic)
        .task { await model.observe() }
        .navEvents(model.vm.navEvents, path: $path)
    }
}

/// The page ground the user picked, and the ink for text drawn straight on it (slides, errors): white on
/// black, `reader-sepia-ink` on paper and sepia.
struct ReaderGround: View {
    let background: ReaderBackground?

    var body: some View { Self.color(background) }

    static func color(_ background: ReaderBackground?) -> Color {
        switch background {
        case .paper: Color.tk.readerPaper
        case .sepia: Color.tk.readerSepia
        default: Color.tk.readerBlack
        }
    }

    static func ink(_ background: ReaderBackground?) -> Color {
        background == nil || background == .black ? .white : Color.tk.readerSepiaInk
    }
}

/// The content state: the layout for the reading mode, the chrome over it, the sheets.
private struct ReaderContentView: View {
    let content: ReaderStateContent
    let vm: ReaderViewModel
    /// The newest state, for the command collector: it outlives any one `content` value.
    let latest: () -> ReaderStateContent?

    @State private var position = ScrollPosition(idType: String.self)
    /// Focus is ignored until the layout has parked: before that it reports wherever a fresh scroll view
    /// starts (the first item, a leading transition), which would move the reader's window for nothing.
    @State private var parked = false
    @State private var zoomed = false
    @State private var prefetcher = ImagePrefetcher(pipeline: .tanukiPages)

    var body: some View {
        ZStack {
            if content.items.isEmpty {
                Text(tr("reader_no_pages", table: .reader))
                    .tkStyle(.body)
                    .foregroundStyle(ReaderGround.ink(content.background))
            } else {
                layout
                    // A new mode is a new layout, parked on `initialItemIndex` — which the ViewModel moves to
                    // the page on screen when the mode changes.
                    .id(content.readingMode)
            }
            ReaderChrome(content: content, vm: vm)
        }
        .sheet(isPresented: Binding(get: { content.sheet != nil }, set: { if !$0 { vm.onDismissSheet() } })) {
            ReaderSheets(content: content, vm: vm)
        }
        .task(id: content.readingMode) { await applyCommands() }
    }

    @ViewBuilder
    private var layout: some View {
        let items = content.items
        let onFocus: (String) -> Void = { key in
            guard parked else { return }
            vm.onItemFocused(key: key)
            prefetch(around: key)
        }
        switch content.readingMode {
        case .webtoon, .continuousVertical:
            WebtoonStrip(items: items, position: $position, ground: content.background, onFocus: onFocus, onTap: vm.onToggleChrome)
                .onAppear { park() }
        default:
            PagedReader(
                items: items,
                axis: content.readingMode == .vertical ? .vertical : .horizontal,
                rightToLeft: content.readingMode == .rightToLeft,
                position: $position,
                zoomed: $zoomed,
                ground: content.background,
                onFocus: onFocus,
                onTap: vm.onToggleChrome
            )
            .onAppear { park() }
        }
    }

    /// First appearance: park on the resume page (or, after a mode switch, the page that was on screen) —
    /// by key, so a list that changed before this ran can't send it to another chapter's page.
    private func park() {
        parked = false
        if let key = content.initialItemKey { position.scrollTo(id: key, anchor: .top) }
        Task { @MainActor in
            // One layout pass for the scroll to land before focus counts again.
            try? await Task.sleep(for: .milliseconds(100))
            parked = true
        }
    }

    /// The ViewModel's scroll commands, by key. A command is sent right after the list it names is
    /// published, which this view may not have drawn yet, so each waits (briefly) for its key to appear.
    private func applyCommands() async {
        for await command in vm.commands {
            let key = command.key
            var waited = 0
            while latest()?.items.contains(where: { $0.key == key }) != true, waited < 20 {
                try? await Task.sleep(for: .milliseconds(50))
                waited += 1
            }
            guard latest()?.items.contains(where: { $0.key == key }) == true else { continue }
            switch onEnum(of: command) {
            case .jump:
                position.scrollTo(id: key, anchor: .top)
            case .reanchor:
                // SwiftUI keeps the item on screen by id by itself; only correct it when it didn't.
                if position.viewID(type: String.self) != key { position.scrollTo(id: key, anchor: .top) }
            }
        }
    }

    /// Warms the page images around [key] — as far as the connection allows (`prefetchBudget`).
    private func prefetch(around key: String) {
        let items = content.items
        guard let index = items.firstIndex(where: { $0.key == key }) else { return }
        let budget = vm.prefetchBudget.value
        let ahead = items[index...].prefix(Int(budget.ahead) + 1)
        let behind = items[..<index].suffix(Int(budget.behind))
        let requests = (behind + ahead).compactMap { ($0 as? ReaderItemPageItem).flatMap(PageRequest.make) }
        prefetcher.stopPrefetching()
        prefetcher.startPrefetching(with: requests)
    }
}

/// The Nuke request for a page: a source page by URL (through `PageDataLoader` → Kotlin), a downloaded
/// one by file URL; decoded at the screen's width, so a tall strip costs what it shows.
enum PageRequest {
    static func make(_ page: ReaderItemPageItem) -> ImageRequest? {
        make(url: page.imageUrl, headers: page.imageHeaders, localPath: page.localPath, width: screenPixels)
    }

    static func make(url: String?, headers: [String: String], localPath: String?, width: CGFloat) -> ImageRequest? {
        let target: URL? = localPath.map { URL(fileURLWithPath: $0) } ?? url.flatMap(URL.init(string:))
        guard let target else { return nil }
        var request = URLRequest(url: target)
        for (name, value) in headers { request.setValue(value, forHTTPHeaderField: name) }
        return ImageRequest(urlRequest: request, processors: [.resize(width: width, unit: .pixels, upscale: false)])
    }

    private static var screenPixels: CGFloat {
        let screen = (UIApplication.shared.connectedScenes.first as? UIWindowScene)?.screen
        return (screen?.bounds.width ?? 430) * (screen?.scale ?? 3)
    }
}
