import Shared
import SwiftUI

/// A Kotlin `Destination` as a navigation-path element.
///
/// `any Destination` isn't `Hashable`, so it can't go into a navigation path directly. Every Kotlin
/// object is an `NSObject` whose `isEqual`/`hash` are Kotlin's `equals`/`hashCode`, so two routes are
/// equal exactly when their destinations are equal data classes.
struct Route: Hashable {
    let destination: any Destination

    private var object: NSObject { destination as! NSObject }

    static func == (lhs: Route, rhs: Route) -> Bool { lhs.object.isEqual(rhs.object) }

    func hash(into hasher: inout Hasher) { hasher.combine(object.hash) }
}

extension View {
    /// Performs a ViewModel's navigation intents (`navigateTo` / `navigateBack`) on the tab's stack. It's
    /// the iOS counterpart of the `navEvents` collection each Compose `*Route` does for the NavHost.
    ///
    /// Collected in a `.task`, so it stops when the screen leaves. A `NavBack` on an empty stack is
    /// ignored: the tab root has nothing to pop.
    func navEvents(_ events: SkieSwiftFlow<any NavEvent>, path: Binding<[Route]>) -> some View {
        task {
            for await event in events {
                switch onEnum(of: event) {
                case let .navTo(navTo):
                    path.wrappedValue.append(Route(destination: navTo.destination))
                case .navBack:
                    if !path.wrappedValue.isEmpty { path.wrappedValue.removeLast() }
                }
            }
        }
    }
}
