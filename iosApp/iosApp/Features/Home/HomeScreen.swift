import Shared
import SwiftUI

/// The Home tab: For You (continue reading + new chapters) and Sources (installed sources by language),
/// switched with a segmented control. All state, including the selected view and the open language
/// group, comes from `HomeViewModel`; this file only draws it and forwards taps.
struct HomeScreen: View {
    @Binding var path: [Route]
    @State private var model = ScreenModel({ $0.homeViewModel() }, uiState: \.uiState)

    var body: some View {
        content
            .scrollContentBackground(.hidden)
            .background(Color.tk.canvas)
            .navigationTitle(tr("home_title", table: .home))
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
            List {
                Picker(selection: segment(content)) {
                    Text(tr("home_segment_for_you", table: .home)).tag(HomeSegment.forYou)
                    Text(tr("home_segment_sources", table: .home)).tag(HomeSegment.sources)
                } label: {
                    EmptyView()
                }
                .pickerStyle(.segmented)
                .listRowBackground(Color.clear)
                .listRowInsets(EdgeInsets())
                .listRowSeparator(.hidden)

                switch content.segment {
                case .forYou: forYou(content)
                case .sources: sources(content)
                }
            }
        }
    }

    private func segment(_ content: HomeStateContent) -> Binding<HomeSegment> {
        Binding(get: { content.segment }, set: { model.vm.onSelectSegment(segment: $0) })
    }

    // MARK: For You

    @ViewBuilder
    private func forYou(_ content: HomeStateContent) -> some View {
        if content.forYouIsEmpty {
            empty(tr("home_for_you_empty", table: .home))
        }
        if !content.continueReading.isEmpty {
            Section(tr("home_continue_reading", table: .home)) {
                ScrollView(.horizontal, showsIndicators: false) {
                    LazyHStack(spacing: TK.Space.s3) {
                        ForEach(content.continueReading, id: \.self) { item in
                            Button { model.vm.onContinueReadingClick(item: item) } label: {
                                MangaHeroCard(
                                    seed: "\(item.mangaId)",
                                    title: item.title,
                                    coverUrl: item.coverUrl,
                                    coverHeaders: item.coverHeaders,
                                    subtitle: chapterLabel(item.chapterNumberLabel, name: item.chapterName)
                                )
                                .frame(width: 325)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .scrollTargetLayout()
                }
                .scrollTargetBehavior(.viewAligned)
                .listRowBackground(Color.clear)
                .listRowInsets(EdgeInsets())
            }
        }
        if !content.newChapters.isEmpty {
            Section(tr("home_new_chapters", table: .home)) {
                ForEach(content.newChapters, id: \.key) { item in
                    Button { model.vm.onNewChapterClick(item: item) } label: { NewChapterRow(item: item) }
                        .listRowBackground(Color.tk.surfaceRaised)
                }
            }
        }
    }

    // MARK: Sources

    @ViewBuilder
    private func sources(_ content: HomeStateContent) -> some View {
        if content.sourceGroups.isEmpty {
            empty(tr("home_sources_empty", table: .home))
        }
        ForEach(content.sourceGroups, id: \.lang) { group in
            Section {
                DisclosureGroup(isExpanded: expanded(group)) {
                    ForEach(group.sources, id: \.id) { source in
                        Button { model.vm.onSourceClick(sourceId: source.id) } label: {
                            HStack(spacing: TK.Space.s3) {
                                SourceIcon(name: source.name, iconUrl: source.iconUrl)
                                Text(source.name)
                                    .tkStyle(.body)
                                    .foregroundStyle(Color.tk.ink)
                                Spacer()
                                Image(systemName: "chevron.forward")
                                    .font(.footnote.weight(.semibold))
                                    .foregroundStyle(Color.tk.inkFaint)
                            }
                        }
                    }
                } label: {
                    Text(group.lang)
                        .tkStyle(.headline)
                        .foregroundStyle(Color.tk.ink)
                }
            }
            .listRowBackground(Color.tk.surfaceRaised)
        }
    }

    private func expanded(_ group: SourceLanguageGroup) -> Binding<Bool> {
        Binding(get: { group.expanded }, set: { _ in model.vm.onToggleLanguage(lang: group.lang) })
    }

    // MARK: Shared pieces

    private func empty(_ message: String) -> some View {
        ContentUnavailableView {
            EmptyView()
        } description: {
            Text(message)
        }
        .listRowBackground(Color.clear)
        .listRowSeparator(.hidden)
    }
}

/// "Chapter 7" when the source numbers its chapters, otherwise the chapter's own name (or nothing).
private func chapterLabel(_ number: String?, name: String) -> String? {
    if let number { return tr("home_continue_reading_chapter", number, table: .home) }
    return name.isEmpty ? nil : name
}

/// One "New Chapters" row: the cover, the series title over the chapter, and when it was found.
private struct NewChapterRow: View {
    let item: NewChapterItem

    var body: some View {
        HStack(spacing: TK.Space.s3) {
            MangaCover(seed: "\(item.mangaId)", url: item.coverUrl, headers: item.coverHeaders, radius: TK.Radius.xs)
                .frame(width: 40)
                .accessibilityLabel(tr("home_cover_content_description", item.title, table: .home))
            VStack(alignment: .leading, spacing: TK.Space.s0_5) {
                Text(item.title)
                    .tkStyle(.headline)
                    .foregroundStyle(Color.tk.ink)
                    .lineLimit(1)
                if let subtitle {
                    Text(subtitle)
                        .tkStyle(.body)
                        .foregroundStyle(Color.tk.inkMuted)
                        .lineLimit(1)
                }
            }
            Spacer(minLength: 0)
            Text(caption(item.discoveredAgo))
                .tkStyle(.caption)
                .foregroundStyle(Color.tk.inkFaint)
        }
    }

    /// One chapter: "Chapter 184" (or its title); a batch: "3 chapters · Ch. 418–421" (or just the count).
    private var subtitle: String? {
        guard item.chapterCount > 1 else { return chapterLabel(item.firstChapter, name: item.chapterName) }
        let count = tr("home_new_chapters_count", Int(item.chapterCount), table: .home)
        guard let first = item.firstChapter, let last = item.lastChapter, first != last else { return count }
        return "\(count) · \(tr("home_chapter_range", first, last, table: .home))"
    }

    private func caption(_ elapsed: Elapsed) -> String {
        let count = Int(elapsed.count)
        return switch elapsed.unit {
        case .justNow: tr("home_time_just_now", table: .home)
        case .minutes: tr("home_time_minutes", count, table: .home)
        case .hours: tr("home_time_hours", count, table: .home)
        case .days: tr("home_time_days", count, table: .home)
        case .weeks: tr("home_time_weeks", count, table: .home)
        }
    }
}
