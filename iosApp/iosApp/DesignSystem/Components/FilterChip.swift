import SwiftUI

/// A selectable capsule chip, optionally with a count: the iOS twin of Android's `TanukiFilterChip`.
/// Solid on purpose, since it often sits on a glass sheet and glass doesn't stack. `surface-raised` at
/// rest, `accent-tint` when selected, `ink` text either way.
struct FilterChip: View {
    let title: String
    var count: Int?
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: TK.Space.s1) {
                Text(title).tkStyle(.label)
                if let count {
                    Text(verbatim: "\(count)")
                        .tkStyle(.numeric)
                        .foregroundStyle(selected ? Color.tk.accent : Color.tk.inkFaint)
                }
            }
            .foregroundStyle(Color.tk.ink)
            .padding(.horizontal, TK.Space.s3)
            .frame(height: 32)
            .background(selected ? Color.tk.accentTint : Color.tk.surfaceRaised, in: Capsule())
            // A hairline so a resting chip still reads on a dark glass sheet.
            .overlay { Capsule().stroke(selected ? Color.clear : Color.tk.line, lineWidth: 1) }
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}
