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
            case .library, .activity: bridge
            }
        case .settingsAppearance: AppearanceScreen()
        case .settingsPrivacy: PrivacyScreen()
        case .settingsCategories: CategoriesScreen()
        case .settingsSources: SourcesScreen()
        case .settingsDownloads: DownloadSettingsScreen(path: $path)
        case .downloads, .mangaDetail, .reader, .sourceBrowse: bridge
        }
    }

    private var bridge: some View {
        ComposeDestinationView(destination: destination, path: $path)
    }
}
