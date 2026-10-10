import Shared
import SwiftUI

/// Appearance: the theme, as a segmented control.
struct AppearanceScreen: View {
    @State private var model = ScreenModel({ $0.appearanceViewModel() }, uiState: \.theme)

    var body: some View {
        List {
            Section(tr("settings_theme", table: .settings)) {
                Picker(tr("settings_theme", table: .settings), selection: theme) {
                    ForEach([ThemePreference.system, .light, .dark], id: \.self) { option in
                        Text(themeLabel(option)).tag(option)
                    }
                }
                .pickerStyle(.segmented)
                .labelsHidden()
                .listRowBackground(Color.clear)
                .listRowInsets(EdgeInsets())
            }
        }
        .settingsList()
        .navigationTitle(tr("settings_appearance", table: .settings))
        .navigationBarTitleDisplayMode(.inline)
        .task { await model.observe() }
    }

    private var theme: Binding<ThemePreference> {
        Binding(get: { model.state }, set: { model.vm.onThemeChange(preference: $0) })
    }
}
