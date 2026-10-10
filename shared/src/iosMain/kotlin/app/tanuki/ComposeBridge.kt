package app.tanuki

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeUIViewController
import app.tanuki.core.navigation.Destination
import app.tanuki.navigation.DestinationContent
import app.tanuki.platform.web.SiteSessionHost
import app.tanuki.platform.web.WebEngineWarmupEffect
import platform.UIKit.UIColor
import platform.UIKit.UIViewController

/*
 * Compose screens hosted inside the SwiftUI root, for the migration period (docs/swiftui-migration.md →
 * "Hybrid bridge"). SwiftUI owns the tabs and stacks; any destination that has no SwiftUI screen yet is
 * rendered by one of these controllers. Delete this file, and Compose from the iOS framework, once the
 * last screen has migrated.
 */

/**
 * One destination's Compose screen. [onNavigate] pushes onto the SwiftUI stack and [onBack] pops it.
 *
 * Each controller supplies its own `ViewModelStoreOwner`, so the screen's ViewModel lives exactly as long
 * as the controller, the counterpart of a `NavBackStackEntry`: popping the SwiftUI destination clears it.
 */
fun DestinationViewController(
    destination: Destination,
    onNavigate: (Destination) -> Unit,
    onBack: () -> Unit
): UIViewController =
    ComposeUIViewController {
        TanukiComposeHost(appGraph) {
            DestinationContent(destination = destination, onNavigate = onNavigate, onBack = onBack)
        }
    }

/**
 * The browser-session layer (Cloudflare challenges, logins) for the SwiftUI root, which mounts it
 * permanently **behind** the app: occluded but laid out and painting, which is all a challenge needs to
 * solve unseen (see [SiteSessionHost]). [onRevealedChange] tells the root when the layer has to come to
 * the front, and when it can go back.
 *
 * Not opaque, with a clear background, so the layer's exit fade shows the app underneath it.
 */
@OptIn(ExperimentalComposeUiApi::class) // `opaque`
fun SiteSessionViewController(onRevealedChange: (Boolean) -> Unit): UIViewController =
    ComposeUIViewController(configure = { opaque = false }) {
        TanukiComposeHost(appGraph) {
            // Start the browser engine early, so the first challenge doesn't pay for it mid-scroll.
            WebEngineWarmupEffect()
            SiteSessionHost(appGraph.siteSessionCoordinator, onRevealedChange)
        }
    }.apply { view.backgroundColor = UIColor.clearColor }
