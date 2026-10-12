import Shared
import SwiftUI

/// The Settings tab: grouped rows, each showing its current value and opening a sub-screen.
struct SettingsScreen: View {
    @Binding var path: [Route]
    @State private var model = ScreenModel({ $0.settingsViewModel() }, uiState: \.uiState)

    var body: some View {
        let state = model.state
        List {
            Section(tr("settings_section_personalization", table: .settings)) {
                row("paintbrush", "settings_appearance", themeLabel(state.theme), .appearance)
            }
            Section(tr("settings_section_privacy", table: .settings)) {
                row(
                    "hand.raised",
                    "settings_privacy",
                    tr(state.crashReportingEnabled ? "settings_privacy_on" : "settings_privacy_off", table: .settings),
                    .privacy
                )
            }
            Section(tr("settings_section_library", table: .settings)) {
                row("folder", "settings_categories",
                    tr("settings_category_count", Int(state.categoryCount), table: .settings), .categories)
                // Above Sources, as the design system places it: it filters the source lists as well as the
                // adult titles inside mixed sources.
                Toggle(isOn: Binding(get: { state.showSensitive }, set: { model.vm.onShowSensitiveChange(show: $0) })) {
                    VStack(alignment: .leading, spacing: TK.Space.s0_5) {
                        Text(tr("settings_show_sensitive", table: .settings)).tkStyle(.body).foregroundStyle(Color.tk.ink)
                        Text(tr("settings_show_sensitive_hint", table: .settings)).tkStyle(.caption).foregroundStyle(Color.tk.inkFaint)
                    }
                }
                .tint(Color.tk.accent)
                .listRowBackground(Color.tk.surfaceRaised)
                row("puzzlepiece.extension", "settings_sources",
                    tr("settings_source_count", Int(state.sourceCount), table: .settings), .sources)
                row(
                    "arrow.down.circle",
                    "settings_downloads",
                    state.downloadQueueCount == 0
                        ? tr("settings_downloads_idle", table: .settings)
                        : tr("settings_download_queue", Int(state.downloadQueueCount), table: .settings),
                    .downloads
                )
            }
        }
        .settingsList()
        .navigationTitle(tr("settings_title", table: .settings))
        .task { await model.observe() }
        .navEvents(model.vm.navEvents, path: $path)
    }

    private func row(_ symbol: String, _ title: String, _ value: String, _ entry: SettingsEntry) -> some View {
        SettingsLinkRow(symbol: symbol, title: tr(title, table: .settings), value: value) {
            model.vm.onEntryClick(entry: entry)
        }
    }
}
