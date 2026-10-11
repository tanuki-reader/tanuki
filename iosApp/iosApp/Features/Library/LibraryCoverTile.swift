import Shared
import SwiftUI

/// One library series: the cover with its title over a bottom wash, and an unread badge. Content, so
/// it stays solid (no glass).
struct LibraryCoverTile: View {
    let item: Shared.LibraryItem

    var body: some View {
        MangaCover(seed: "\(item.mangaId)", url: item.coverUrl, headers: item.coverHeaders)
            .overlay(alignment: .bottomLeading) {
                Text(item.title)
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
                if item.unreadCount > 0 {
                    Text(verbatim: "\(item.unreadCount)")
                        .tkStyle(.numeric)
                        .foregroundStyle(Color.tk.onAccent)
                        .padding(.horizontal, TK.Space.s2)
                        .frame(minWidth: 22, minHeight: 22)
                        .background(Color.tk.accent, in: Capsule())
                        .padding(TK.Space.s1)
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: TK.Radius.sm, style: .continuous))
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(tr("library_cover_content_description", item.title, table: .library))
    }
}
