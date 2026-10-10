import Shared
import SwiftUI

/// Sources: what is installed, what can be installed again, and removing one (optionally with its
/// downloads). The removal dialog is ViewModel state. iOS alerts can't hold a switch, so Android's
/// "also delete downloads" switch becomes a second destructive button here, which sets the same option.
struct SourcesScreen: View {
    @State private var model = ScreenModel({ $0.sourcesViewModel() }, uiState: \.uiState)

    var body: some View {
        let state = model.state
        content(state)
            .navigationTitle(tr("settings_sources", table: .settings))
            .navigationBarTitleDisplayMode(.inline)
            .alert(
                tr("settings_remove_source_title", state.pendingRemoval?.name ?? "", table: .settings),
                // State-driven only: every button tells the ViewModel itself (see CategoriesScreen.dialog).
                isPresented: Binding(get: { state.pendingRemoval != nil }, set: { _ in })
            ) {
                Button(tr("settings_remove", table: .settings), role: .destructive) { model.vm.onRemovalConfirmed() }
                if let usage = state.pendingUsage, !usage.isEmpty {
                    Button(tr("settings_remove_source_downloads", Int(usage.chapters), table: .settings),
                           role: .destructive) {
                        model.vm.onRemovalDeleteDownloadsChange(deleteDownloads: true)
                        model.vm.onRemovalConfirmed()
                    }
                }
                Button(tr("settings_cancel", table: .settings), role: .cancel) { model.vm.onRemovalDismissed() }
            } message: {
                Text(removalMessage(state))
            }
            .task { await model.observe() }
    }

    @ViewBuilder
    private func content(_ state: SourcesState) -> some View {
        if state.loading {
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity).background(Color.tk.canvas)
        } else if state.installed.isEmpty && state.available.isEmpty {
            ContentUnavailableView { EmptyView() } description: {
                Text(tr("settings_sources_empty", table: .settings))
            }
            .background(Color.tk.canvas)
        } else {
            List {
                if !state.installed.isEmpty {
                    Section(tr("settings_sources_installed", table: .settings)) {
                        ForEach(state.installed, id: \.sourceId) { source in
                            row(source, state: state, installed: true)
                                .swipeActions {
                                    Button(tr("settings_remove", table: .settings), role: .destructive) {
                                        model.vm.onRemovalRequested(sourceId: source.sourceId)
                                    }
                                }
                        }
                    }
                }
                if !state.available.isEmpty {
                    Section {
                        ForEach(state.available, id: \.sourceId) { source in
                            row(source, state: state, installed: false)
                        }
                    } header: {
                        Text(tr("settings_sources_available", table: .settings))
                    } footer: {
                        Text(tr("settings_sources_available_hint", table: .settings))
                    }
                }
            }
            .settingsList()
        }
    }

    private func row(_ source: SourceItem, state: SourcesState, installed: Bool) -> some View {
        let failed = state.failedIds.contains(source.sourceId)
        return HStack(spacing: TK.Space.s3) {
            SourceIcon(name: source.name, iconUrl: source.iconUrl)
            VStack(alignment: .leading, spacing: TK.Space.s0_5) {
                Text(source.name).tkStyle(.body).foregroundStyle(Color.tk.ink)
                if failed {
                    Text(tr(installed ? "settings_source_remove_failed" : "settings_source_install_failed",
                            table: .settings))
                        .tkStyle(.caption)
                        .foregroundStyle(Color.tk.danger)
                } else if let lang = source.lang {
                    Text(lang).tkStyle(.caption).foregroundStyle(Color.tk.inkFaint)
                }
            }
            Spacer()
            if state.busyIds.contains(source.sourceId) {
                ProgressView()
            } else if installed {
                Button(tr("settings_remove", table: .settings), role: .destructive) {
                    model.vm.onRemovalRequested(sourceId: source.sourceId)
                }
                .buttonStyle(.borderless)
                .accessibilityLabel(tr("settings_remove_source_action", source.name, table: .settings))
            } else {
                Button(tr("settings_install", table: .settings)) { model.vm.onInstall(sourceId: source.sourceId) }
                    .buttonStyle(.borderless)
            }
        }
        .listRowBackground(Color.tk.surfaceRaised)
    }

    /// The message, plus what deleting the downloads would free once that is known.
    private func removalMessage(_ state: SourcesState) -> String {
        let message = tr("settings_remove_source_message", table: .settings)
        guard let usage = state.pendingUsage, !usage.isEmpty else { return message }
        return message + "\n\n" + tr("settings_remove_source_downloads_hint", fileSize(usage.bytes), table: .settings)
    }
}
