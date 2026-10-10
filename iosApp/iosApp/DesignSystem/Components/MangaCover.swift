import SwiftUI

/// A series' cover art, cropped to the design system's cover ratio (5:7) with continuous corners and a
/// hairline. With no art (no URL, still loading, or failed), one of the six `cover-*` grounds shows
/// instead, picked by hashing `seed` (the manga id).
///
/// The caller sets the width; the height follows. Use `TK.Radius.xs` for thumbnails at 40pt and below.
struct MangaCover: View {
    let seed: String
    let url: String?
    var headers: [String: String] = [:]
    var radius: CGFloat = TK.Radius.sm

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: radius, style: .continuous)
        // The fallback ground sizes the cover; the image fills it rather than setting its own size.
        CoverSeed.ground(seed)
            .aspectRatio(TK.Size.coverRatio, contentMode: .fit)
            .overlay {
                TanukiImage(url: url, headers: headers) { Color.clear }
            }
            .clipShape(shape)
            .overlay { shape.stroke(Color.tk.line, lineWidth: 0.5) }
    }
}

enum CoverSeed {
    /// The fallback `cover-*` ground for `seed`.
    static func ground(_ seed: String) -> Color {
        Color.tk.coverFallbacks[index(seed, count: Color.tk.coverFallbacks.count)]
    }

    /// The fallback slot for `seed`. Ports Kotlin's `String.hashCode` (Java's: s[0]·31^(n-1) + … over
    /// UTF-16 units, wrapping at Int32), which Android's `coverGradientColors` uses, so a series lands on
    /// the same slot on both platforms.
    static func index(_ seed: String, count: Int) -> Int {
        let hash = seed.utf16.reduce(Int32(0)) { $0 &* 31 &+ Int32($1) }
        let n = Int32(count)
        return Int((hash % n + n) % n)
    }
}
