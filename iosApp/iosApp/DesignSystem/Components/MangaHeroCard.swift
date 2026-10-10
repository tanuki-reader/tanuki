import SwiftUI

/// A wide "hero" card for a carousel: the cover, blurred and dimmed, washes the panel; the title sits on
/// the left over a veil, and the real cover stands at the trailing edge. The iOS twin of Android's
/// `MangaHeroCard`. Content, so it is solid: no glass.
///
/// The caller sets the width; the height is fixed so a row of cards lines up.
struct MangaHeroCard: View {
    let seed: String
    let title: String
    let coverUrl: String?
    var coverHeaders: [String: String] = [:]
    var subtitle: String?

    private static let height: CGFloat = 168
    private static let coverWidth: CGFloat = 92

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: TK.Radius.lg, style: .continuous)
        HStack(spacing: TK.Space.s4) {
            VStack(alignment: .leading, spacing: TK.Space.s1) {
                Text(title)
                    .tkStyle(.headline)
                    .foregroundStyle(Color.tk.ink)
                    .lineLimit(3)
                if let subtitle {
                    Text(subtitle)
                        .tkStyle(.caption)
                        .foregroundStyle(Color.tk.inkMuted)
                        .lineLimit(1)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            MangaCover(seed: seed, url: coverUrl, headers: coverHeaders)
                .frame(width: Self.coverWidth)
        }
        .padding(TK.Space.s4)
        .frame(height: Self.height)
        .background {
            ZStack {
                Color.tk.surface
                TanukiImage(url: coverUrl, headers: coverHeaders) { CoverSeed.ground(seed) }
                    .blur(radius: 16)
                    .opacity(0.45)
                // Near-opaque under the title, clearing toward the cover so the art shows.
                LinearGradient(
                    colors: [Color.tk.canvas.opacity(0.88), Color.tk.canvas.opacity(0.15)],
                    startPoint: .leading,
                    endPoint: .trailing
                )
            }
        }
        .clipShape(shape)
        .overlay { shape.stroke(Color.tk.line, lineWidth: 0.5) }
        .contentShape(shape)
    }
}
