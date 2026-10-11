import Shared
import SwiftUI

/// The app's root on iOS: one tab per `TopLevelDestination`, each with its own `NavigationStack` of
/// `Route`s (CLAUDE.md → "Navigation: shared contract, native rendering").
///
/// Tab selection and paths are UI chrome, with no logic, so they live here. What gets pushed comes from
/// the screens' ViewModels (`navEvents`) or from Compose screens' `onNavigate` while they're still on the
/// bridge.
///
/// The browser-session layer is always mounted, *behind* the tabs: a Cloudflare challenge solves there
/// out of sight, and the layer comes to the front only if it needs the user (see `SiteSessionHost`).
struct RootView: View {
    @State private var tab: TabKey = .home
    @State private var paths: [TabKey: [Route]] = [:]
    @State private var sessionRevealed = false
    @State private var colorScheme: ColorScheme?

    var body: some View {
        ZStack {
            SiteSessionLayer(revealed: $sessionRevealed)
                .ignoresSafeArea()
                .zIndex(sessionRevealed ? 1 : -1)
            TabView(selection: $tab) {
                ForEach(TabKey.allCases, id: \.self) { key in
                    Tab(key.title, systemImage: key.systemImage, value: key) {
                        stack(for: key)
                    }
                }
            }
            .tabBarMinimizeBehavior(.onScrollDown)
        }
        .preferredColorScheme(colorScheme)
        .task {
            for await preference in IosAppKt.observeThemePreference() {
                colorScheme = switch preference {
                case .system: nil
                case .light: .light
                case .dark: .dark
                }
            }
        }
    }

    private func stack(for key: TabKey) -> some View {
        let path = Binding(get: { paths[key] ?? [] }, set: { paths[key] = $0 })
        return NavigationStack(path: path) {
            DestinationView(destination: key.destination, path: path)
                .navigationDestination(for: Route.self) { route in
                    // Leaves take the whole screen, as they do on Android: the tab bar steps aside.
                    DestinationView(destination: route.destination, path: path)
                        .toolbar(.hidden, for: .tabBar)
                }
        }
    }
}

/// The tabs, in `TopLevelDestination.entries` order. A Swift enum because `TabView` selection and the
/// path dictionary need a `Hashable` key, and the titles/icons are the iOS dialect's own.
private enum TabKey: CaseIterable {
    case library, home, activity, settings

    var destination: any TopLevelDestination {
        switch self {
        case .library: TopLevelDestinationLibrary.shared
        case .home: TopLevelDestinationHome.shared
        case .activity: TopLevelDestinationActivity.shared
        case .settings: TopLevelDestinationSettings.shared
        }
    }

    var title: LocalizedStringKey {
        switch self {
        case .library: "tab_library"
        case .home: "tab_home"
        case .activity: "tab_activity"
        case .settings: "tab_settings"
        }
    }

    var systemImage: String {
        switch self {
        case .library: "books.vertical"
        case .home: "house"
        case .activity: "chart.line.uptrend.xyaxis"
        case .settings: "gearshape"
        }
    }
}
