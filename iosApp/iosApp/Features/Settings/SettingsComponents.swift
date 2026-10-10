import Foundation
import Shared
import SwiftUI

/// A Settings row that opens a sub-screen: a tinted symbol tile, the title, the current value, and a
/// chevron (the design system's iOS `ListRow`).
struct SettingsLinkRow: View {
    let symbol: String
    let title: String
    var value: String?
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: TK.Space.s3) {
                Image(systemName: symbol)
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(Color.tk.onAccent)
                    .frame(width: 28, height: 28)
                    .background(Color.tk.accent, in: .rect(cornerRadius: TK.Radius.sm, style: .continuous))
                Text(title)
                    .tkStyle(.body)
                    .foregroundStyle(Color.tk.ink)
                Spacer()
                if let value {
                    Text(value)
                        .tkStyle(.body)
                        .foregroundStyle(Color.tk.inkMuted)
                        .lineLimit(1)
                }
                Image(systemName: "chevron.forward")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(Color.tk.inkFaint)
            }
        }
        .listRowBackground(Color.tk.surfaceRaised)
    }
}

extension View {
    /// The look every Settings screen shares: inset-grouped rows on the canvas.
    func settingsList() -> some View {
        listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
            .background(Color.tk.canvas)
    }
}

/// The copy for each theme option, shared by the index's subtitle and the Appearance picker.
func themeLabel(_ theme: ThemePreference) -> String {
    switch theme {
    case .system: tr("settings_theme_system", table: .settings)
    case .light: tr("settings_theme_light", table: .settings)
    case .dark: tr("settings_theme_dark", table: .settings)
    }
}

/// A byte count as the system's own storage screens show it (decimal units, like Android's).
func fileSize(_ bytes: Int64) -> String {
    ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
}
