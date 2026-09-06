package app.tanuki.download

import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.domain.common.service.info
import app.tanuki.core.domain.common.service.warn
import app.tanuki.core.domain.download.model.DownloadProgress
import app.tanuki.core.domain.download.service.DownloadNotifier
import app.tanuki.shared.resources.Res
import app.tanuki.shared.resources.notification_downloads_done
import app.tanuki.shared.resources.notification_downloads_failed
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jetbrains.compose.resources.getPluralString
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter
import kotlin.coroutines.resume

/**
 * iOS [DownloadNotifier]: a local notification when the queue stops, and nothing while it runs.
 *
 * The missing half is not an omission. A progress notification is Android's answer to a queue that
 * keeps running with the app closed; on iOS the queue *doesn't* — it runs in the foreground with a short
 * grace period (see [IosDownloadScheduler]) — so there is no stretch of time where a user would be
 * looking at a progress notification instead of the queue screen itself. Posting one anyway would mean
 * a notification that appears and vanishes while the app is open in front of them.
 *
 * What is worth saying is that a run finished, because by then the app has usually gone to sleep and the
 * chapters became readable offline without the user seeing it happen. iOS suppresses a local
 * notification's banner while the app is foregrounded by default, so the same call is quiet when they
 * are already looking.
 *
 * Copy is resolved with Compose Resources' non-composable `getPluralString`, and authorization is
 * checked but never requested — both for the reasons `IosLibraryUpdateNotifier` documents.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class IosDownloadNotifier(
    private val logger: Logger,
) : DownloadNotifier {
    /** Deliberately empty; see the class KDoc. */
    override suspend fun onProgress(progress: DownloadProgress) = Unit

    override suspend fun onFinished(
        downloaded: Int,
        failed: Int,
    ) {
        val center = UNUserNotificationCenter.currentNotificationCenter()
        if (!center.isAuthorized()) {
            logger.info(TAG, "Notifications are not authorized; the finished queue goes unannounced")
            return
        }

        val title =
            if (failed > 0) {
                getPluralString(Res.plurals.notification_downloads_failed, failed, failed)
            } else {
                getPluralString(Res.plurals.notification_downloads_done, downloaded, downloaded)
            }
        val content =
            UNMutableNotificationContent().apply {
                setTitle(title)
                setSound(UNNotificationSound.defaultSound())
            }

        // A fixed identifier, so a later run replaces this rather than stacking beside it. A null
        // trigger delivers immediately.
        val request =
            UNNotificationRequest.requestWithIdentifier(
                identifier = NOTIFICATION_ID,
                content = content,
                trigger = null,
            )
        center.addNotificationRequest(request) { error ->
            if (error != null) logger.warn(TAG, "Could not post download notification: ${error.localizedDescription}")
        }
    }

    /** Nothing is posted while a run is going, so there is nothing to take down. */
    override suspend fun clear() = Unit

    /** Provisional counts as authorized — quiet delivery is still delivery. */
    private suspend fun UNUserNotificationCenter.isAuthorized(): Boolean =
        suspendCancellableCoroutine { continuation ->
            getNotificationSettingsWithCompletionHandler { settings ->
                val status = settings?.authorizationStatus
                continuation.resume(
                    status == UNAuthorizationStatusAuthorized || status == UNAuthorizationStatusProvisional,
                )
            }
        }

    private companion object {
        const val TAG = "DownloadNotifier"

        /** Fixed, so each run replaces the previous notification instead of adding to a pile. */
        const val NOTIFICATION_ID = "app.tanuki.downloads"
    }
}