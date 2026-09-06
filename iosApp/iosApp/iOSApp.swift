import SwiftUI
import Sentry

import Shared

@main
struct iOSApp: App {
    init() {
        SentrySDK.start { options in
            options.dsn = "https://46f9e0117a644b91b8c7ab34e06567ae@o4511837132292096.ingest.de.sentry.io/4511837251043408"

            // Adds IP for users.
            // For more information, visit: https://docs.sentry.io/platforms/apple/data-management/data-collected/
            options.sendDefaultPii = true

            // Set tracesSampleRate to 1.0 to capture 100% of transactions for performance monitoring.
            // We recommend adjusting this value in production.
            options.tracesSampleRate = 1.0

            // Configure profiling. Visit https://docs.sentry.io/platforms/apple/profiling/ to learn more.
            options.configureProfiling = {
                $0.sessionSampleRate = 1.0 // We recommend adjusting this value in production.
                $0.lifecycle = .trace
            }

            // Uncomment the following lines to add more data to your events
            // options.attachScreenshot = true // This adds a screenshot to the error events
            // options.attachViewHierarchy = true // This adds the view hierarchy to the error events
            
            // Enable experimental logging features
            options.experimental.enableLogs = true
        }

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
        }
    }
}
