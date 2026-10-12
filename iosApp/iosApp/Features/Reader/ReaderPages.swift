import NukeUI
import Shared
import SwiftUI

// MARK: - Webtoon strip

/// Long strip: pages stacked with no gaps, scrolled freely. Reports every key on screen (the reader focuses
/// the topmost) and whether the strip is moving.
/// Pinch or double-tap zooms the whole strip (not one page), so neighbouring pages stay aligned; while
/// zoomed, a horizontal drag walks the page sideways.
struct WebtoonStrip: View {
    let items: [any ReaderItem]
    @Binding var position: ScrollPosition
    let ground: ReaderBackground
    let ratios: PageRatios
    let onVisible: ([String]) -> Void
    let onScrolling: (Bool) -> Void
    let onTap: () -> Void

    /// Width:height reserved for a page before its image arrives (Android's `DEFAULT_PAGE_ASPECT_RATIO`).
    private static let placeholderRatio: CGFloat = 0.7

    @State private var scale: CGFloat = 1
    @State private var pinchStart: CGFloat = 1
    @State private var offsetX: CGFloat = 0
    @State private var dragStart: CGFloat = 0

    var body: some View {
        GeometryReader { geo in
            ScrollView(.vertical) {
                LazyVStack(spacing: 0) {
                    ForEach(items, id: \.key) { item in
                        ReaderItemView(
                            item: item,
                            ground: ground,
                            fill: false,
                            placeholderHeight: geo.size.width / (ratios.of(item.key) ?? Self.placeholderRatio),
                            onSize: { ratios.record(item.key, $0) }
                        )
                            .frame(minHeight: item is ReaderItemPageItem ? 0 : geo.size.height)
                    }
                }
                .scrollTargetLayout()
            }
            .scrollPosition($position, anchor: .top)
            .onChange(of: items.count) { ratios.retain(items.map(\.key)) }
            .onScrollTargetVisibilityChange(idType: String.self, threshold: 0.01, onVisible)
            .onScrollPhaseChange { _, phase in onScrolling(phase != .idle) }
            .scaleEffect(scale, anchor: .top)
            .offset(x: offsetX)
            .gesture(
                MagnifyGesture()
                    .onChanged { value in scale = min(max(pinchStart * value.magnification, 1), 4) }
                    .onEnded { _ in
                        pinchStart = scale
                        offsetX = clamp(offsetX, width: geo.size.width)
                    }
            )
            .simultaneousGesture(
                DragGesture(minimumDistance: 12)
                    .onChanged { value in
                        guard scale > 1 else { return }
                        offsetX = clamp(dragStart + value.translation.width, width: geo.size.width)
                    }
                    .onEnded { _ in dragStart = offsetX }
            )
            .onTapGesture(count: 2) {
                withAnimation(TK.Motion.enter) {
                    scale = scale > 1 ? 1 : 2
                    pinchStart = scale
                    offsetX = 0
                    dragStart = 0
                }
            }
            .onTapGesture(perform: onTap)
            .clipped()
        }
    }

    /// Keeps the scaled strip covering the viewport sideways: no margin beside the page.
    private func clamp(_ x: CGFloat, width: CGFloat) -> CGFloat {
        let slack = width * (scale - 1) / 2
        return min(max(x, -slack), slack)
    }
}

/// The real width:height of every strip page that has loaded, for as long as its chapter is in the window
/// (Android's `PageAspectRatios`). A page that scrolls away is torn down, and its decoded image usually
/// leaves Nuke's memory cache too (a tall page at screen width is megabytes). Back on screen it would
/// start as a `placeholderRatio` slot and then grow to its real height. Scrolling up, that growth happens
/// at the top of the screen and shoves everything below it down. Not observed: the ratio only matters
/// when a page's view is built, and once its image is in the image sizes itself.
final class PageRatios {
    private var ratios: [String: CGFloat] = [:]

    func of(_ key: String) -> CGFloat? { ratios[key] }

    func record(_ key: String, _ size: CGSize) {
        if size.width > 0, size.height > 0 { ratios[key] = size.width / size.height }
    }

    /// Forgets the pages whose chapter left the window.
    func retain(_ keys: [String]) {
        let live = Set(keys)
        ratios = ratios.filter { live.contains($0.key) }
    }
}

// MARK: - Paged

/// One page per swipe: horizontal (L→R, or R→L laid out right to left) or vertical. Each page zooms on its
/// own; while one is zoomed, the pager stops paging so a pan moves the page instead.
struct PagedReader: View {
    let items: [any ReaderItem]
    let axis: Axis
    let rightToLeft: Bool
    @Binding var position: ScrollPosition
    @Binding var zoomed: Bool
    let ground: ReaderBackground
    let onVisible: ([String]) -> Void
    let onScrolling: (Bool) -> Void
    let onTap: () -> Void

    var body: some View {
        ScrollView(axis == .horizontal ? .horizontal : .vertical) {
            stack
        }
        .scrollTargetBehavior(.paging)
        .scrollIndicators(.hidden)
        .scrollDisabled(zoomed)
        .scrollPosition($position)
        .onScrollTargetVisibilityChange(idType: String.self, threshold: 0.6, onVisible)
        .onScrollPhaseChange { _, phase in onScrolling(phase != .idle) }
        // Right to left: the pager runs from the right edge; each page is laid back out left to right.
        .environment(\.layoutDirection, rightToLeft ? .rightToLeft : .leftToRight)
    }

    @ViewBuilder
    private var stack: some View {
        // `scrollTargetLayout` on the lazy stack itself: on a wrapper around it (an `if`), the scroll view
        // sees no targets — no paging snap to items, and no visibility reports.
        if axis == .horizontal {
            LazyHStack(spacing: 0) { pages }.scrollTargetLayout()
        } else {
            LazyVStack(spacing: 0) { pages }.scrollTargetLayout()
        }
    }

    private var pages: some View {
        ForEach(items, id: \.key) { item in
            ZoomablePage(zoomed: $zoomed, onTap: onTap) {
                ReaderItemView(item: item, ground: ground, fill: true, placeholderHeight: nil)
            }
            .environment(\.layoutDirection, .leftToRight)
            .containerRelativeFrame([.horizontal, .vertical])
        }
    }
}

/// A page that pinches and double-taps to zoom, and pans while zoomed.
private struct ZoomablePage<Content: View>: View {
    @Binding var zoomed: Bool
    let onTap: () -> Void
    @ViewBuilder let content: () -> Content

    @State private var scale: CGFloat = 1
    @State private var pinchStart: CGFloat = 1
    @State private var offset: CGSize = .zero
    @State private var dragStart: CGSize = .zero

    var body: some View {
        GeometryReader { geo in
            content()
                .frame(width: geo.size.width, height: geo.size.height)
                .scaleEffect(scale)
                .offset(offset)
                .gesture(
                    MagnifyGesture()
                        .onChanged { value in setScale(pinchStart * value.magnification, in: geo.size) }
                        .onEnded { _ in pinchStart = scale }
                )
                .simultaneousGesture(
                    DragGesture()
                        .onChanged { value in
                            guard scale > 1 else { return }
                            offset = clamp(CGSize(width: dragStart.width + value.translation.width,
                                                  height: dragStart.height + value.translation.height), in: geo.size)
                        }
                        .onEnded { _ in dragStart = offset },
                    including: scale > 1 ? .all : .subviews
                )
                .onTapGesture(count: 2) {
                    withAnimation(TK.Motion.enter) { setScale(scale > 1 ? 1 : 2, in: geo.size) }
                    pinchStart = scale
                }
                .onTapGesture(perform: onTap)
        }
        .clipped()
        .onDisappear {
            if scale > 1 { zoomed = false }
            scale = 1; pinchStart = 1; offset = .zero; dragStart = .zero
        }
    }

    private func setScale(_ value: CGFloat, in size: CGSize) {
        scale = min(max(value, 1), 4)
        if scale <= 1 { offset = .zero; dragStart = .zero }
        offset = clamp(offset, in: size)
        zoomed = scale > 1
    }

    private func clamp(_ value: CGSize, in size: CGSize) -> CGSize {
        let slackX = size.width * (scale - 1) / 2
        let slackY = size.height * (scale - 1) / 2
        return CGSize(width: min(max(value.width, -slackX), slackX), height: min(max(value.height, -slackY), slackY))
    }
}

/// The visible key that comes first in reading order. The visibility callback doesn't promise any order,
/// and reporting whichever key it listed first moved the reader into a page lower on screen.
func topmost(_ keys: [String], in items: [any ReaderItem]) -> String? {
    guard keys.count > 1 else { return keys.first }
    let visible = Set(keys)
    return items.first(where: { visible.contains($0.key) })?.key
}

// MARK: - Items

/// A page, a transition slide between two chapters, or the end slide.
struct ReaderItemView: View {
    let item: any ReaderItem
    let ground: ReaderBackground
    /// Paged: fit the page to the screen. Strip: full width, natural height.
    let fill: Bool
    /// Strip only: the height a page holds until its image is in. Without it the slot collapsed to the
    /// spinner's size, so a whole unloaded chapter fit on one screen and a single swipe ran through
    /// several chapters.
    let placeholderHeight: CGFloat?
    /// Strip only: the loaded page's size, so it can hold that height the next time it is built.
    var onSize: ((CGSize) -> Void)? = nil

    var body: some View {
        switch onEnum(of: item) {
        case let .pageItem(page):
            PageImage(page: page, fill: fill, placeholderHeight: placeholderHeight, ink: ReaderGround.ink(ground), onSize: onSize)
        case let .transition(transition):
            TransitionSlide(transition: transition, ink: ReaderGround.ink(ground))
        case .end:
            EndSlide(ink: ReaderGround.ink(ground))
        }
    }
}

private struct PageImage: View {
    let page: ReaderItemPageItem
    let fill: Bool
    let placeholderHeight: CGFloat?
    let ink: Color
    let onSize: ((CGSize) -> Void)?

    var body: some View {
        LazyImage(request: PageRequest.make(page)) { state in
            if let image = state.image {
                image.resizable().aspectRatio(contentMode: .fit)
            } else if state.error != nil {
                Image(systemName: "exclamationmark.triangle")
                    .font(.largeTitle)
                    .foregroundStyle(ink.opacity(0.6))
                    .frame(maxWidth: .infinity, maxHeight: fill ? .infinity : nil)
                    .frame(height: placeholderHeight)
            } else {
                // Before the page's size is known: a placeholder at a typical page's proportions.
                ProgressView()
                    .tint(ink)
                    .frame(maxWidth: .infinity, maxHeight: fill ? .infinity : nil)
                    .frame(height: placeholderHeight)
            }
        }
        .pipeline(.tanukiPages)
        .onCompletion { result in
            if case let .success(response) = result { onSize?(response.image.size) }
        }
        .accessibilityLabel(tr("reader_page_indicator", Int(page.pageNumber) + 1, Int(page.pageCount), table: .reader))
    }
}

private struct TransitionSlide: View {
    let transition: ReaderItemTransition
    let ink: Color

    var body: some View {
        VStack(spacing: TK.Space.s5) {
            section(tr("reader_transition_finished", table: .reader), title(transition.from), emphasized: false)
            section(tr("reader_transition_up_next", table: .reader), title(transition.to), emphasized: true)
            if transition.loading {
                ProgressView().tint(ink)
            } else {
                Text(tr("reader_transition_swipe_hint", table: .reader))
                    .tkStyle(.caption)
                    .foregroundStyle(ink.opacity(0.6))
            }
        }
        .multilineTextAlignment(.center)
        .padding(TK.Space.s8)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .contentShape(Rectangle())
    }

    private func section(_ label: String, _ title: String, emphasized: Bool) -> some View {
        VStack(spacing: TK.Space.s1) {
            Text(label)
                .tkStyle(.overline)
                .foregroundStyle(emphasized ? Color.tk.accent : ink.opacity(0.6))
            Text(title)
                .tkStyle(emphasized ? .headline : .body)
                .foregroundStyle(emphasized ? ink : ink.opacity(0.6))
        }
    }

    private func title(_ chapter: ChapterRef) -> String {
        chapter.name ?? tr("reader_chapter_number", chapter.numberLabel ?? "", table: .reader)
    }
}

private struct EndSlide: View {
    let ink: Color

    var body: some View {
        VStack(spacing: TK.Space.s3) {
            Image(systemName: "books.vertical")
                .font(.system(size: 48))
                .foregroundStyle(ink.opacity(0.5))
            Text(tr("reader_end_title", table: .reader))
                .tkStyle(.headline)
                .foregroundStyle(ink)
            Text(tr("reader_end_subtitle", table: .reader))
                .tkStyle(.caption)
                .foregroundStyle(ink.opacity(0.6))
        }
        .multilineTextAlignment(.center)
        .padding(TK.Space.s8)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .contentShape(Rectangle())
    }
}
