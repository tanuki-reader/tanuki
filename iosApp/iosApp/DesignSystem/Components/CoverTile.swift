import SwiftUI

/// A series as a grid tile: the cover with its title over a bottom wash, an optional unread badge, and
/// an optional "In library" pill. Content, so it stays solid (no glass). Used by the library grid and
/// the source screens.
struct CoverTile: View {
    let seed: String
    let title: String
    let coverUrl: String?
    var coverHeaders: [String: String] = [:]
    var unread: Int = 0
    /// The localized "In library" text; `nil` hides the pill.
    var inLibraryLabel: String?
    /// VoiceOver label for the whole tile.
    let accessibilityLabel: String

    var body: some View {
        MangaCover(seed: seed, url: coverUrl, headers: coverHeaders)
            .overlay(alignment: .bottomLeading) {
                Text(title)
                    .tkStyle(.tileTitle)
                    .foregroundStyle(Color.tk.readerPaper)
                    .lineLimit(2)
                    .multilineTextAlignment(.leading)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, TK.Space.s2)
                    .padding(.top, TK.Space.s6)
                    .padding(.bottom, TK.Space.s2)
                    .background(
                        LinearGradient(colors: [Color.tk.coverScrim, .clear], startPoint: .bottom, endPoint: .top)
                    )
            }
            .overlay(alignment: .topTrailing) {
                if unread > 0 {
                    Text(verbatim: "\(unread)")
                        .tkStyle(.numeric)
                        .foregroundStyle(Color.tk.onAccent)
                        .padding(.horizontal, TK.Space.s2)
                        .frame(minWidth: 22, minHeight: 22)
                        .background(Color.tk.accent, in: Capsule())
                        .padding(TK.Space.s1)
                }
            }
            .overlay(alignment: .topLeading) {
                if let inLibraryLabel {
                    // Words with the glyph: an icon alone carries no meaning.
                    Label(inLibraryLabel, systemImage: "checkmark")
                        .labelStyle(.titleAndIcon)
                        .tkStyle(.caption)
                        .foregroundStyle(Color.tk.ink)
                        .padding(.horizontal, TK.Space.s2)
                        .padding(.vertical, TK.Space.s0_5)
                        .background(Color.tk.surface, in: Capsule())
                        .padding(TK.Space.s1)
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: TK.Radius.sm, style: .continuous))
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(accessibilityLabel)
    }
}
