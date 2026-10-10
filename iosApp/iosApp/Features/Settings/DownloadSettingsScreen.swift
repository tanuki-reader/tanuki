import Shared
import SwiftUI

/// Download settings: automatic downloads, the network rule, deleting after reading, and storage. Every
/// control writes straight through the ViewModel, so nothing here mirrors a stored value.
struct DownloadSettingsScreen: View {
    @Binding var path: [Route]
    @State private var model = ScreenModel({ $0.downloadSettingsViewModel() }, uiState: \.uiState)

    var body: some View {
        let state = model.state
        List {
            autoDownload(state)
            Section {
                Toggle(isOn: Binding(get: { state.settings.onWifiOnly }, set: { model.vm.onWifiOnlyChange(wifiOnly: $0) })) {
                    labelled("settings_wifi_only", hint: "settings_wifi_only_hint")
                }
                .listRowBackground(Color.tk.surfaceRaised)
            } header: {
                Text(tr("settings_section_network", table: .settings))
            }
            deleteAfterReading(state)
            storage(state)
        }
        .settingsList()
        .navigationTitle(tr("settings_downloads_title", table: .settings))
        .navigationBarTitleDisplayMode(.inline)
        .alert(
            tr("settings_storage_delete_all_title", table: .settings),
            // State-driven only: each button tells the ViewModel itself.
            isPresented: Binding(get: { state.confirmingDeleteAll }, set: { _ in })
        ) {
            Button(tr("settings_storage_delete_all_cancel", table: .settings), role: .cancel) {
                model.vm.onDeleteAllDismissed()
            }
            Button(tr("settings_storage_delete_all_confirm", table: .settings), role: .destructive) {
                model.vm.onDeleteAllDownloads()
            }
        } message: {
            Text(tr("settings_storage_delete_all_message", table: .settings))
        }
        .task { await model.observe() }
        .navEvents(model.vm.navEvents, path: $path)
    }

    // MARK: Sections

    @ViewBuilder
    private func autoDownload(_ state: DownloadSettingsState) -> some View {
        Section {
            Picker(selection: Binding(
                get: { state.settings.autoDownloadMode },
                set: { model.vm.onAutoDownloadModeChange(mode: $0) }
            )) {
                labelled("settings_auto_download_off", hint: "settings_auto_download_off_hint").tag(AutoDownloadMode.off)
                labelled("settings_auto_download_all", hint: "settings_auto_download_all_hint").tag(AutoDownloadMode.allLibrary)
                if state.canSelectCategories {
                    labelled("settings_auto_download_categories", hint: "settings_auto_download_categories_hint")
                        .tag(AutoDownloadMode.selectedCategories)
                }
            } label: {
                EmptyView()
            }
            .pickerStyle(.inline)
            .listRowBackground(Color.tk.surfaceRaised)
        } header: {
            Text(tr("settings_section_auto_download", table: .settings))
        }
        if state.showCategoryPicker {
            Section {
                ForEach(state.categoryOptions, id: \.id) { option in
                    Button { model.vm.onAutoDownloadCategoryToggle(categoryId: option.id) } label: {
                        HStack {
                            Text(option.name).tkStyle(.body).foregroundStyle(Color.tk.ink)
                            Spacer()
                            if option.selected {
                                Image(systemName: "checkmark").foregroundStyle(Color.tk.accent)
                            }
                        }
                    }
                    .listRowBackground(Color.tk.surfaceRaised)
                }
            } footer: {
                if state.needsCategoryPick {
                    Text(tr("settings_auto_download_pick", table: .settings))
                }
            }
        }
    }

    private func deleteAfterReading(_ state: DownloadSettingsState) -> some View {
        Section {
            Picker(selection: Binding(
                get: { state.settings.deleteAfterReading },
                set: { model.vm.onDeleteAfterReadingChange(setting: $0) }
            )) {
                labelled("settings_delete_never", hint: "settings_delete_never_hint").tag(DeleteAfterReading.off)
                labelled("settings_delete_immediately", hint: "settings_delete_immediately_hint")
                    .tag(DeleteAfterReading.immediately)
                labelled("settings_delete_keep_one", hint: "settings_delete_keep_one_hint")
                    .tag(DeleteAfterReading.keepLastOne)
                labelled("settings_delete_keep_five", hint: "settings_delete_keep_five_hint")
                    .tag(DeleteAfterReading.keepLastFive)
            } label: {
                EmptyView()
            }
            .pickerStyle(.inline)
            .listRowBackground(Color.tk.surfaceRaised)
        } header: {
            Text(tr("settings_section_delete_after_reading", table: .settings))
        } footer: {
            if state.settings.deleteAfterReading.isEnabled {
                Text(tr("settings_delete_not_retroactive", table: .settings))
            }
        }
    }

    @ViewBuilder
    private func storage(_ state: DownloadSettingsState) -> some View {
        Section {
            SettingsLinkRow(
                symbol: "arrow.down.circle",
                title: tr("settings_storage_open_queue", table: .settings),
                action: { model.vm.onOpenQueue() }
            )
            if let usage = state.usage {
                if usage.isEmpty {
                    Text(tr("settings_storage_empty", table: .settings))
                        .tkStyle(.body)
                        .foregroundStyle(Color.tk.inkFaint)
                        .listRowBackground(Color.tk.surfaceRaised)
                } else {
                    Text(tr("settings_storage_total", fileSize(usage.totalBytes), table: .settings))
                        .tkStyle(.headline)
                        .foregroundStyle(Color.tk.ink)
                        .listRowBackground(Color.tk.surfaceRaised)
                    ForEach(state.listedSeries, id: \.self) { series in
                        VStack(alignment: .leading, spacing: TK.Space.s0_5) {
                            Text(series.title).tkStyle(.body).foregroundStyle(Color.tk.ink).lineLimit(1)
                            Text(tr("settings_storage_series_chapters", Int(series.chapterCount),
                                    fileSize(series.bytes), table: .settings))
                                .tkStyle(.caption)
                                .foregroundStyle(Color.tk.inkMuted)
                        }
                        .listRowBackground(Color.tk.surfaceRaised)
                    }
                }
            } else {
                ProgressView()
                    .frame(maxWidth: .infinity)
                    .listRowBackground(Color.tk.surfaceRaised)
            }
        } header: {
            Text(tr("settings_section_storage", table: .settings))
        } footer: {
            Text(tr("settings_storage_open_queue_hint", table: .settings))
        }
        // The one irreversible action: its own last group, in `danger`, confirmed first.
        if state.hasDownloads {
            Section {
                Button(tr("settings_storage_delete_all", table: .settings), role: .destructive) {
                    model.vm.onDeleteAllRequested()
                }
                .disabled(state.busy)
                .listRowBackground(Color.tk.surfaceRaised)
            }
        }
    }

    // MARK: Pieces

    private func labelled(_ title: String, hint: String) -> some View {
        VStack(alignment: .leading, spacing: TK.Space.s0_5) {
            Text(tr(title, table: .settings)).tkStyle(.body).foregroundStyle(Color.tk.ink)
            Text(tr(hint, table: .settings)).tkStyle(.caption).foregroundStyle(Color.tk.inkMuted)
        }
    }
}
