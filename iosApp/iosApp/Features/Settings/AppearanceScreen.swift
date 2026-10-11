import Shared
import SwiftUI

/// Appearance: the theme and the library cover size, each a segmented control.
struct AppearanceScreen: View {
    @State private var model = ScreenModel({ $0.appearanceViewModel() }, uiState: \.uiState)

    var body: some View {
        // A plain stack, not a `List`: a list re-renders its cells on every state change, which cuts
        // the segmented controls' slide short and makes the selection jump.
        ScrollView {
            VStack(alignment: .leading, spacing: TK.Space.s6) {
                group("settings_theme") {
                    Picker(tr("settings_theme", table: .settings), selection: theme) {
                        ForEach([ThemePreference.system, .light, .dark], id: \.self) { option in
                            Text(themeLabel(option)).tag(option)
                        }
                    }
                    .pickerStyle(.segmented)
                    .labelsHidden()
                }
                group("settings_library_grid", hint: "settings_library_grid_hint") {
                    Picker(tr("settings_library_grid", table: .settings), selection: gridSize) {
                        Text(tr("settings_library_grid_small", table: .settings)).tag(LibraryGridSize.small)
                        Text(tr("settings_library_grid_medium", table: .settings)).tag(LibraryGridSize.medium)
                        Text(tr("settings_library_grid_large", table: .settings)).tag(LibraryGridSize.large)
                    }
                    .pickerStyle(.segmented)
                    .labelsHidden()
                }
            }
            .padding(.horizontal, TK.Space.s5)
            .padding(.vertical, TK.Space.s4)
        }
        .background(Color.tk.canvas)
        .navigationTitle(tr("settings_appearance", table: .settings))
        .navigationBarTitleDisplayMode(.inline)
        .task { await model.observe() }
    }

    /// A section-style caption over a control, with an optional hint under it.
    private func group(_ title: String, hint: String? = nil, @ViewBuilder content: () -> some View) -> some View {
        VStack(alignment: .leading, spacing: TK.Space.s2) {
            Text(tr(title, table: .settings))
                .tkStyle(.overline)
                .foregroundStyle(Color.tk.inkMuted)
            content()
            if let hint {
                Text(tr(hint, table: .settings))
                    .tkStyle(.caption)
                    .foregroundStyle(Color.tk.inkMuted)
            }
        }
    }

    private var theme: Binding<ThemePreference> {
        Binding(get: { model.state.theme }, set: { model.vm.onThemeChange(preference: $0) })
    }

    private var gridSize: Binding<LibraryGridSize> {
        Binding(get: { model.state.gridSize }, set: { model.vm.onGridSizeChange(size: $0) })
    }
}
