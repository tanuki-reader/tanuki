import Shared
import SwiftUI

/// Privacy: the crash-reporting opt-in, what it sends and never sends, and resetting the anonymous id.
struct PrivacyScreen: View {
    @State private var model = ScreenModel({ $0.privacyViewModel() }, uiState: \.uiState)

    var body: some View {
        let state = model.state
        List {
            Section {
                Toggle(tr("settings_crash_reporting_switch", table: .settings), isOn: crashReporting(state))
                    .listRowBackground(Color.tk.surfaceRaised)
            } header: {
                Text(tr("settings_section_crash_reporting", table: .settings))
            } footer: {
                VStack(alignment: .leading, spacing: TK.Space.s2) {
                    Text(tr("settings_crash_reporting_collected", table: .settings))
                    Text(tr("settings_crash_reporting_never", table: .settings))
                }
            }
            Section {
                Button(tr("settings_reset_identifier", table: .settings)) { model.vm.onResetIdentifier() }
                    .listRowBackground(Color.tk.surfaceRaised)
            } footer: {
                Text(tr(state.identifierReset ? "settings_reset_identifier_done" : "settings_reset_identifier_explain",
                        table: .settings))
            }
        }
        .settingsList()
        .navigationTitle(tr("settings_privacy", table: .settings))
        .navigationBarTitleDisplayMode(.inline)
        .task { await model.observe() }
    }

    private func crashReporting(_ state: PrivacyState) -> Binding<Bool> {
        Binding(get: { state.crashReportingEnabled }, set: { model.vm.onCrashReportingChange(enabled: $0) })
    }
}
