import Shared
import SwiftUI

/// The sort & filter sheet: a Sort | Filter switch over one pane at a time, on the system's Liquid
/// Glass sheet (nothing here paints a background). Every control writes straight through the
/// ViewModel, which saves it; the grid behind re-derives live, so "Show N titles" only closes the sheet.
struct LibrarySheet: View {
    let content: LibraryStateContent
    let vm: LibraryViewModel
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: TK.Space.s5) {
                    Picker(tr("library_sheet_title", table: .library), selection: tab) {
                        Text(tr("library_sheet_tab_sort", table: .library)).tag(LibrarySheetTab.sort)
                        Text(filterTabTitle).tag(LibrarySheetTab.filter)
                    }
                    .pickerStyle(.segmented)
                    .labelsHidden()

                    // The panes overlap while they swap, sliding the way Android's AnimatedContent does:
                    // Filter in from the trailing edge, Sort back from the leading one.
                    ZStack(alignment: .topLeading) {
                        switch content.sheetTab {
                        case .sort: sortPane.transition(paneTransition(from: .leading))
                        case .filter: filterPane.transition(paneTransition(from: .trailing))
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .padding(.horizontal, TK.Space.s5)
                .padding(.bottom, TK.Space.s5)
                .animation(reduceMotion ? TK.Motion.reduced : TK.Motion.enter, value: content.sheetTab)
            }
            .navigationTitle(tr("library_sheet_title", table: .library))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button(tr("library_sheet_reset", table: .library)) { vm.onResetFilters() }
                        .disabled(!content.filterActive && content.sort == LibrarySort.companion.Default)
                }
                ToolbarItem(placement: .topBarTrailing) {
                    let count = content.items.count
                    Button(tr("library_sheet_show_count", count, table: .library)) { vm.toLibraryState() }
                        .buttonStyle(.glassProminent)
                        .tint(Color.tk.accent)
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    /// Slide + fade from `edge`; a plain crossfade under Reduce Motion.
    private func paneTransition(from edge: Edge) -> AnyTransition {
        reduceMotion ? .opacity : .move(edge: edge).combined(with: .opacity)
    }

    private var tab: Binding<LibrarySheetTab> {
        Binding(get: { content.sheetTab }, set: { vm.onSheetTabChange(tab: $0) })
    }

    /// "Filter", with a dot while any filter is on (Android's tab does the same).
    private var filterTabTitle: String {
        let title = tr("library_sheet_tab_filter", table: .library)
        return content.filterActive ? "\(title) •" : title
    }

    // MARK: Sort

    private var sortPane: some View {
        VStack(alignment: .leading, spacing: TK.Space.s1) {
            ForEach([LibrarySort.Mode.alphabetical, .dateAdded, .lastRead], id: \.self) { mode in
                let selected = content.sort.mode == mode
                Button { vm.onSortModeClick(mode: mode) } label: {
                    HStack(spacing: TK.Space.s2) {
                        Text(modeLabel(mode))
                            .tkStyle(.body)
                            .fontWeight(selected ? .semibold : .regular)
                            .foregroundStyle(Color.tk.ink)
                        Spacer()
                        if selected {
                            Text(directionLabel(content.sort)).tkStyle(.caption)
                            Image(systemName: content.sort.direction == .ascending ? "arrow.up" : "arrow.down")
                        }
                    }
                    .foregroundStyle(Color.tk.accent)
                    .padding(.horizontal, TK.Space.s4)
                    .frame(height: TK.Size.tapIos)
                    .background(
                        selected ? Color.tk.accentTint : .clear,
                        in: RoundedRectangle(cornerRadius: TK.Radius.md, style: .continuous)
                    )
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
            Text(tr("library_sort_hint", table: .library))
                .tkStyle(.caption)
                .foregroundStyle(Color.tk.inkMuted)
                .padding(.horizontal, TK.Space.s4)
                .padding(.top, TK.Space.s2)
        }
    }

    // MARK: Filter

    private var filterPane: some View {
        VStack(alignment: .leading, spacing: TK.Space.s5) {
            group("library_sheet_section_status") {
                FlowLayout {
                    FilterChip(title: tr("library_status_all", table: .library), selected: content.filter.readingStatus == nil) {
                        vm.onReadingStatusChange(status: nil)
                    }
                    ForEach([ReadingStatus.reading, .completed, .onHold, .dropped, .planningToRead], id: \.self) { status in
                        let selected = content.filter.readingStatus == status
                        // Tapping the active status clears it back to "Any".
                        FilterChip(title: statusLabel(status), selected: selected) {
                            vm.onReadingStatusChange(status: selected ? nil : status)
                        }
                    }
                }
            }
            group("library_sheet_section_downloaded") {
                triState(
                    ["library_downloaded_any", "library_downloaded_yes", "library_downloaded_no"],
                    value: content.filter.downloaded
                ) { vm.onDownloadedChange(triState: $0) }
            }
            group("library_sheet_section_publication") {
                triState(
                    ["library_publication_any", "library_publication_completed", "library_publication_ongoing"],
                    value: content.filter.publicationCompleted
                ) { vm.onPublicationCompletedChange(triState: $0) }
            }
            if !content.categories.isEmpty { categories }
        }
    }

    private var categories: some View {
        let matchMode = content.filter.categoryMatchMode
        return group("library_sheet_section_categories") {
            Picker(tr("library_sheet_section_categories", table: .library), selection: Binding(
                get: { matchMode },
                set: { vm.onCategoryMatchModeChange(mode: $0) }
            )) {
                Text(tr("library_category_match_any", table: .library)).tag(CategoryMatchMode.any)
                Text(tr("library_category_match_all", table: .library)).tag(CategoryMatchMode.all)
            }
            .pickerStyle(.segmented)
            .labelsHidden()
            Text(tr(matchMode == .all ? "library_category_help_all" : "library_category_help_any", table: .library))
                .tkStyle(.caption)
                .foregroundStyle(Color.tk.inkMuted)
            FlowLayout {
                ForEach(content.categories, id: \.id) { chip in
                    FilterChip(title: chip.name, count: Int(chip.count), selected: chip.isEnabled) {
                        vm.onCategoryToggle(categoryId: chip.id)
                    }
                    .opacity(chip.count == 0 && !chip.isEnabled ? 0.55 : 1)
                }
            }
        }
    }

    /// An overline caption over a filter control.
    private func group(_ title: String, @ViewBuilder content: () -> some View) -> some View {
        VStack(alignment: .leading, spacing: TK.Space.s2) {
            Text(tr(title, table: .library))
                .tkStyle(.overline)
                .foregroundStyle(Color.tk.inkMuted)
            content()
        }
    }

    /// A tri-state filter as a segmented control: off / required / excluded, in that order.
    private func triState(_ labels: [String], value: TriState, onChange: @escaping (TriState) -> Void) -> some View {
        Picker(tr(labels[0], table: .library), selection: Binding(get: { value }, set: onChange)) {
            ForEach(Array(zip([TriState.disabled, .required, .excluded], labels)), id: \.0) { state, label in
                Text(tr(label, table: .library)).tag(state)
            }
        }
        .pickerStyle(.segmented)
        .labelsHidden()
    }

    // MARK: Copy

    private func modeLabel(_ mode: LibrarySort.Mode) -> String {
        switch mode {
        case .alphabetical: tr("library_sort_alphabetical", table: .library)
        case .dateAdded: tr("library_sort_date_added", table: .library)
        case .lastRead: tr("library_sort_last_read", table: .library)
        }
    }

    private func directionLabel(_ sort: LibrarySort) -> String {
        let up = sort.direction == .ascending
        return switch sort.mode {
        case .alphabetical: tr(up ? "library_sort_alphabetical_asc" : "library_sort_alphabetical_desc", table: .library)
        case .dateAdded: tr(up ? "library_sort_date_added_asc" : "library_sort_date_added_desc", table: .library)
        case .lastRead: tr(up ? "library_sort_last_read_asc" : "library_sort_last_read_desc", table: .library)
        }
    }

    private func statusLabel(_ status: ReadingStatus) -> String {
        switch status {
        case .reading: tr("library_status_reading", table: .library)
        case .completed: tr("library_status_completed", table: .library)
        case .onHold: tr("library_status_on_hold", table: .library)
        case .dropped: tr("library_status_dropped", table: .library)
        case .planningToRead: tr("library_status_planning", table: .library)
        }
    }
}
