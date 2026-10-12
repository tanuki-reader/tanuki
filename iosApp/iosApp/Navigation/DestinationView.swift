import Shared
import SwiftUI

/// The screen for a destination. `onEnum(of:)` is exhaustive, so a new `Destination` doesn't build until
/// iOS renders it.
struct DestinationView: View {
    let destination: any Destination
    @Binding var path: [Route]

    var body: some View {
        switch onEnum(of: destination) {
        case let .topLevelDestination(tab):
            switch onEnum(of: tab) {
            case .home: HomeScreen(path: $path)
            case .settings: SettingsScreen(path: $path)
            case .library: LibraryScreen(path: $path)
            case .activity: ActivityScreen(path: $path)
            case .search: GlobalSearchScreen(query: nil, path: $path)
            }
        case .settingsAppearance: AppearanceScreen()
        case .settingsPrivacy: PrivacyScreen()
        case .settingsCategories: CategoriesScreen()
        case .settingsSources: SourcesScreen()
        case .settingsDownloads: DownloadSettingsScreen(path: $path)
        case let .sourceBrowse(source): SourceHomeScreen(sourceId: source.sourceId, path: $path)
        case let .sourceCatalogGrid(catalog):
            SourceCatalogScreen(sourceId: catalog.sourceId, listing: catalog.listing, query: catalog.query, path: $path)
        case let .globalSearch(search): GlobalSearchScreen(query: search.query, path: $path)
        case let .mangaDetail(manga): MangaScreen(mangaId: manga.mangaId, path: $path)
        case .downloads: DownloadsScreen(path: $path)
        case .bookmarks: BookmarksScreen(path: $path)
        case let .reader(reader): ReaderScreen(mangaId: reader.mangaId, chapterId: reader.chapterId, page: reader.page, path: $path)
        }
    }
}
