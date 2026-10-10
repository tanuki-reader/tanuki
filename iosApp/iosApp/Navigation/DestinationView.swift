import Shared
import SwiftUI

/// The screen for a destination: the SwiftUI screen where one exists, otherwise the Compose `*Route` on
/// the bridge. Add a case here as each feature migrates; when every case is native, the bridge goes.
struct DestinationView: View {
    let destination: any Destination
    @Binding var path: [Route]

    var body: some View {
        if case let .topLevelDestination(tab) = onEnum(of: destination), case .home = onEnum(of: tab) {
            HomeScreen(path: $path)
        } else {
            ComposeDestinationView(destination: destination, path: $path)
        }
    }
}
