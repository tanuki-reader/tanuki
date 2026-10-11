import Shared
import SwiftUI

/// The screen for a destination: the SwiftUI screen where one exists, otherwise the Compose `*Route` on
/// the bridge. Add a case here as each feature migrates; when every case is native, the bridge goes.
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
            case .activity: bridge
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
        case .downloads, .reader: bridge
        }
    }

    private var bridge: some View {
        ComposeDestinationView(destination: destination, path: $path)
    }
}
