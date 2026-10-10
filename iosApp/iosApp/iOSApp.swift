import SwiftUI

import Shared

@main
struct iOSApp: App {
    init() {
        // Must happen before launch finishes: iOS requires every BGTaskScheduler identifier to be
        // registered by then and throws otherwise. Doing it here, rather than when the Compose view
        // is created, is the difference between "registered in time" and a launch-time crash.
        IosBackgroundTasksKt.registerLibraryUpdateTask()
        IosDownloadTasksKt.registerDownloadTask()

        // Asked while the app is on screen — the only moment the user has any context for the
        // question, and iOS shows this prompt once ever. The background refresh that actually posts
        // notifications cannot ask: by then there is no app on screen to explain why.
        IosLibraryUpdateNotifierKt.requestNotificationAuthorization()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                // Scene content only appears for a launch with UI. A background task launch runs init
                // but never this, which is the point (see AppStartup). Idempotent, so re-appearing is
                // harmless.
                .task { MainViewControllerKt.startApp() }
        }
    }
}
