import Shared
import SwiftUI
import UIKit

/// A destination that has no SwiftUI screen yet, rendered by its Compose `*Route` (the migration's
/// hybrid bridge, `ComposeBridge.kt`). Pushes and pops go to the SwiftUI stack it sits in, so a Compose
/// screen navigates exactly like a migrated one.
///
/// Compose draws its own top bar, so the native navigation bar is hidden. Compose keeps owning its
/// status-bar and keyboard insets, as it did as the whole UI, so only the top edge and the keyboard are
/// ignored. The bottom stays inside the safe area, above the tab bar.
struct ComposeDestinationView: View {
    let destination: any Destination
    @Binding var path: [Route]

    var body: some View {
        ComposeController(destination: destination, path: $path)
            .ignoresSafeArea(edges: .top)
            .ignoresSafeArea(.keyboard)
            .toolbar(.hidden, for: .navigationBar)
    }
}

private struct ComposeController: UIViewControllerRepresentable {
    let destination: any Destination
    @Binding var path: [Route]

    func makeUIViewController(context: Context) -> UIViewController {
        // The binding is captured once. SwiftUI's Binding reads and writes the live path, so later pushes
        // land on the stack as it is at the time.
        let path = $path
        return ComposeBridgeKt.DestinationViewController(
            destination: destination,
            onNavigate: { path.wrappedValue.append(Route(destination: $0)) },
            onBack: { if !path.wrappedValue.isEmpty { path.wrappedValue.removeLast() } }
        )
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

/// The browser-session layer (`SiteSessionHost`), mounted permanently by the root. It reports when it has
/// to come to the front.
struct SiteSessionLayer: UIViewControllerRepresentable {
    @Binding var revealed: Bool

    func makeUIViewController(context: Context) -> UIViewController {
        let revealed = $revealed
        return ComposeBridgeKt.SiteSessionViewController(onRevealedChange: { revealed.wrappedValue = $0.boolValue })
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

/// UIKit turns the edge-swipe back gesture off when a stack hides its navigation bar, which every Compose
/// leaf does since it draws its own top bar. This turns it back on, for any stack deeper than its root.
extension UINavigationController: @retroactive UIGestureRecognizerDelegate {
    override open func viewDidLoad() {
        super.viewDidLoad()
        interactivePopGestureRecognizer?.delegate = self
    }

    public func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        viewControllers.count > 1
    }
}
