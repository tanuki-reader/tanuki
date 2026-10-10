import SwiftUI

/// A source's badge: its remote icon when it reports one, over a lettered `accent` tile that also shows
/// while the icon loads or if it fails.
struct SourceIcon: View {
    let name: String
    let iconUrl: String?

    private static let size: CGFloat = 34

    var body: some View {
        TanukiImage(url: iconUrl) {
            ZStack {
                Color.tk.accent
                Text(name.prefix(1).uppercased())
                    .tkStyle(.headline)
                    .foregroundStyle(Color.tk.onAccent)
            }
        }
        .frame(width: Self.size, height: Self.size)
        .clipShape(RoundedRectangle(cornerRadius: TK.Radius.sm, style: .continuous))
    }
}
