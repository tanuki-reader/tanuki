package app.tanuki.update

import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.domain.common.service.info
import app.tanuki.core.domain.common.service.warn
import app.tanuki.core.domain.update.model.MangaUpdateSummary
import app.tanuki.core.domain.update.service.LibraryUpdateNotifier
import app.tanuki.shared.resources.Res
import app.tanuki.shared.resources.notification_updates_chapters
import app.tanuki.shared.resources.notification_updates_line
import app.tanuki.shared.resources.notification_updates_title
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter
import kotlin.coroutines.resume

/**
 * iOS [LibraryUpdateNotifier], posting one local notification through [UNUserNotificationCenter].
 *
 * Copy is resolved with Compose Resources' **non-composable** `getString`/`getPluralString`, which
 * follow the system locale — the right source for something read on the lock screen, outside any
 * composition.
 *
 * Authorization is checked, never requested. iOS shows its permission prompt once, ever, and showing
 * it from a background refresh — with no app on screen to explain why — spends that one chance badly.
 * [requestNotificationAuthorization] asks at launch instead; if the user declined, this quietly does
 * nothing.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class IosLibraryUpdateNotifier(
    private val logger: Logger
) : LibraryUpdateNotifier {
    override suspend fun notifyNewChapters(summaries: List<MangaUpdateSummary>) {
        if (summaries.isEmpty()) return

        val center = UNUserNotificationCenter.currentNotificationCenter()
        if (!center.isAuthorized()) {
            logger.info(TAG, "Notifications are not authorized; skipping ${summaries.size} update(s)")
            return
        }

        val content =
            UNMutableNotificationContent().apply {
                setTitle(summaries.title())
                setBody(summaries.body())
                setSound(UNNotificationSound.defaultSound())
            }

        // A fixed identifier, so a later run replaces this notification rather than stacking beside
        // it: it always says what is currently waiting, not what one run happened to find. A null
        // trigger delivers immediately.
        val request =
            UNNotificationRequest.requestWithIdentifier(
                identifier = NOTIFICATION_ID,
                content = content,
                trigger = null,
            )
        center.addNotificationRequest(request) { error ->
            if (error != null) logger.warn(TAG, "Could not post update notification: ${error.localizedDescription}")
        }
    }

    /** One series speaks for itself; several are counted. */
    private suspend fun List<MangaUpdateSummary>.title(): String =
        singleOrNull()?.title ?: getPluralString(Res.plurals.notification_updates_title, size, size)

    /**
     * iOS has no inbox style — a notification body is one text run — so several series become a joined
     * list. It stays readable because the body expands over multiple lines when the user pulls the
     * notification down.
     */
    private suspend fun List<MangaUpdateSummary>.body(): String {
        val single = singleOrNull()
        if (single != null) return single.chapterCountText()
        // Resolved with `map` (inline, so suspend calls are allowed) before joining — `joinToString`
        // takes a non-inline transform and cannot call a suspend function.
        val lines = map { getString(Res.string.notification_updates_line, it.title, it.chapterCountText()) }
        return lines.joinToString(SUMMARY_SEPARATOR)
    }

    private suspend fun MangaUpdateSummary.chapterCountText(): String =
        getPluralString(Res.plurals.notification_updates_chapters, newChapterCount, newChapterCount)

    /**
     * Bridges `getNotificationSettings`' completion handler to a suspend call.
     *
     * Provisional counts as authorized: it is the "quiet delivery" state iOS can grant without asking,
     * and a silently-delivered update notification is still one the user can find.
     */
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
        const val TAG = "LibraryUpdateNotifier"

        /** Fixed, so each run replaces the previous notification instead of adding to a pile. */
        const val NOTIFICATION_ID = "app.tanuki.library-update"

        const val SUMMARY_SEPARATOR = ", "
    }
}

/**
 * Asks the user to allow notifications, if they have not been asked before.
 *
 * Called from the iOS host at launch rather than from the notifier, because iOS presents this prompt
 * exactly once in an app's lifetime: asking while the app is open and visible is the only moment the
 * user has any context for the question. Asking later, from a background refresh, would burn that one
 * chance on a prompt appearing out of nowhere.
 *
 * Re-calling it after the user has answered is harmless — iOS returns the existing decision without
 * showing anything.
 */
fun requestNotificationAuthorization() {
    UNUserNotificationCenter.currentNotificationCenter().requestAuthorizationWithOptions(
        options = UNAuthorizationOptionAlert or UNAuthorizationOptionBadge or UNAuthorizationOptionSound,
    ) { _, _ -> }
}