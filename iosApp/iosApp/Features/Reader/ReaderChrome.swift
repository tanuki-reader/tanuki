import Shared
import SwiftUI

/// The reader's chrome: clear Liquid Glass floating over the page, shown on tap and faded by the ViewModel
/// after a few idle seconds. Nothing of it is on screen otherwise (the design system keeps the reader
/// chrome-less by default).
///
/// - **Top:** back · chapter title over the series name · bookmark this page · ⋯ (open in browser).
/// - **Bottom:** previous chapter · page scrubber with "12 / 48" · next chapter, then **Chapters** and
///   **Reader settings**.
struct ReaderChrome: View {
    let content: ReaderStateContent
    let vm: ReaderViewModel

    @Environment(\.openURL) private var openURL
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        VStack {
            topBar
            Spacer()
            bottomBar
        }
        .padding(.horizontal, TK.Space.s4)
        .padding(.vertical, TK.Space.s2)
        .opacity(content.chromeVisible ? 1 : 0)
        .allowsHitTesting(content.chromeVisible)
        .animation(reduceMotion ? TK.Motion.reduced : (content.chromeVisible ? TK.Motion.enter : TK.Motion.exit), value: content.chromeVisible)
    }

    // MARK: Top

    private var topBar: some View {
        GlassEffectContainer {
            HStack(spacing: TK.Space.s2) {
                Button { vm.onBack() } label: { Image(systemName: "chevron.backward") }
                    .buttonStyle(.glass(.clear))
                    .accessibilityLabel(tr("reader_back", table: .reader))
                VStack(alignment: .leading, spacing: 0) {
                    Text(chapterTitle)
                        .tkStyle(.label)
                        .lineLimit(1)
                    // Content from the source, never localized.
                    Text(content.mangaTitle)
                        .tkStyle(.caption)
                        .foregroundStyle(Color.tk.inkMuted)
                        .lineLimit(1)
                }
                .padding(.horizontal, TK.Space.s4)
                .padding(.vertical, TK.Space.s2)
                .frame(maxWidth: .infinity, alignment: .leading)
                .glassEffect(.clear, in: .capsule)
                Button { vm.onToggleBookmark() } label: {
                    Image(systemName: content.isCurrentPageBookmarked ? "bookmark.fill" : "bookmark")
                        .symbolEffect(.bounce, value: content.isCurrentPageBookmarked)
                        .foregroundStyle(content.isCurrentPageBookmarked ? Color.tk.accent : Color.tk.ink)
                }
                .buttonStyle(.glass(.clear))
                .accessibilityLabel(tr(content.isCurrentPageBookmarked ? "reader_bookmark_remove" : "reader_bookmark_add", table: .reader))
                Menu {
                    Button(tr("reader_open_in_browser", table: .reader), systemImage: "safari") {
                        if let url = URL(string: content.chapterUrl) { openURL(url) }
                    }
                } label: {
                    Image(systemName: "ellipsis")
                }
                .buttonStyle(.glass(.clear))
                .accessibilityLabel(tr("reader_more", table: .reader))
            }
        }
    }

    // MARK: Bottom

    private var bottomBar: some View {
        GlassEffectContainer {
            VStack(spacing: TK.Space.s2) {
                HStack(spacing: TK.Space.s2) {
                    Button { vm.onPreviousChapter() } label: { Image(systemName: "backward.end.fill") }
                        .buttonStyle(.glass(.clear))
                        .disabled(!content.hasPreviousChapter)
                        .accessibilityLabel(tr("reader_previous_chapter", table: .reader))
                    PageScrubber(content: content, vm: vm)
                        .padding(.horizontal, TK.Space.s4)
                        .padding(.vertical, TK.Space.s1)
                        .glassEffect(.clear, in: .capsule)
                    Button { vm.onNextChapter() } label: { Image(systemName: "forward.end.fill") }
                        .buttonStyle(.glass(.clear))
                        .disabled(!content.hasNextChapter)
                        .accessibilityLabel(tr("reader_next_chapter", table: .reader))
                }
                HStack(spacing: TK.Space.s2) {
                    Button { vm.onOpenSheet(sheet: .chapters) } label: {
                        Label(tr("reader_chapters", table: .reader), systemImage: "list.bullet")
                    }
                    Button { vm.onOpenSheet(sheet: .settings) } label: {
                        Label(tr("reader_settings", table: .reader), systemImage: "textformat.size")
                    }
                }
                .buttonStyle(.glass(.clear))
                .tkStyle(.label)
            }
        }
    }

    private var chapterTitle: String {
        content.chapterName ?? tr("reader_chapter_number", content.chapterNumberLabel ?? "", table: .reader)
    }
}

/// "12 / 48" over a slider through the current chapter's pages. Dragging previews the page in the label;
/// the jump happens once, on release, and the chrome stays up while a finger is on it. Right to left, it
/// runs right to left too.
private struct PageScrubber: View {
    let content: ReaderStateContent
    let vm: ReaderViewModel

    @State private var dragging: Double?

    var body: some View {
        let count = Int(content.currentPageCount)
        let shown = Int(dragging ?? Double(content.currentPage))
        VStack(spacing: 0) {
            Text(tr("reader_page_indicator", shown + 1, max(count, 1), table: .reader))
                .tkStyle(.caption)
                .monospacedDigit()
            if count > 1 {
                Slider(
                    value: Binding(get: { dragging ?? Double(content.currentPage) }, set: { dragging = $0 }),
                    in: 0...Double(count - 1),
                    step: 1
                ) { editing in
                    vm.onScrubbingChange(active: editing)
                    if !editing, let page = dragging {
                        vm.onScrubToPage(page: Int32(page))
                        dragging = nil
                    }
                }
                .tint(Color.tk.accent)
                .environment(\.layoutDirection, content.readingMode == .rightToLeft ? .rightToLeft : .leftToRight)
            }
        }
    }
}
