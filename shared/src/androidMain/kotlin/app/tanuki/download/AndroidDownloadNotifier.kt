package app.tanuki.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.work.ForegroundInfo
import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.domain.common.service.info
import app.tanuki.core.domain.download.model.DownloadProgress
import app.tanuki.core.domain.download.service.DownloadNotifier
import app.tanuki.shared.R
import app.tanuki.shared.resources.Res
import app.tanuki.shared.resources.notification_channel_downloads
import app.tanuki.shared.resources.notification_channel_downloads_description
import app.tanuki.shared.resources.notification_channel_downloads_done
import app.tanuki.shared.resources.notification_channel_downloads_done_description
import app.tanuki.shared.resources.notification_downloads_chapter
import app.tanuki.shared.resources.notification_downloads_done
import app.tanuki.shared.resources.notification_downloads_failed
import app.tanuki.shared.resources.notification_downloads_pages
import app.tanuki.shared.resources.notification_downloads_remaining
import app.tanuki.shared.resources.notification_downloads_starting
import app.tanuki.shared.resources.notification_downloads_title
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString

/**
 * Android [DownloadNotifier]: one ongoing progress notification while the queue runs, and one summary
 * when it stops.
 *
 * The progress notification is not decoration — the queue runs inside a `dataSync` foreground service,
 * and this notification is what makes that legal. [foregroundInfo] is how [DownloadWorker] hands it to
 * WorkManager; `onProgress` then keeps updating the *same* id, so the service's notification and the
 * one the user watches are the same object rather than two that can drift apart.
 *
 * Two channels, because the two say different things and deserve different settings: progress is
 * `IMPORTANCE_LOW` (no sound, no peek — it is a status line, and a queue of fifty chapters would
 * otherwise buzz fifty times), while the summary is `IMPORTANCE_DEFAULT` so a finished or failed queue
 * can actually get attention. A user who wants one and not the other can turn each off separately,
 * which is the whole point of channels.
 *
 * Built on the platform [NotificationManager] and copy resolved with Compose Resources' non-composable
 * `getString`/`getPluralString`, for the same reasons as `AndroidLibraryUpdateNotifier` — see its KDoc.
 * Permission is checked, never requested: `MainActivity` asks, and this quietly does nothing if the user
 * declined rather than failing the run.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class AndroidDownloadNotifier(
    private val context: Context,
    private val logger: Logger,
) : DownloadNotifier {
    override suspend fun onProgress(progress: DownloadProgress) {
        val manager = context.notificationManager() ?: return
        manager.ensureProgressChannel()
        manager.notify(PROGRESS_NOTIFICATION_ID, progress.toNotification())
    }

    override suspend fun onFinished(
        downloaded: Int,
        failed: Int,
    ) {
        val manager = context.notificationManager() ?: return
        manager.ensureDoneChannel()

        val title =
            if (failed > 0) {
                getPluralString(Res.plurals.notification_downloads_failed, failed, failed)
            } else {
                getPluralString(Res.plurals.notification_downloads_done, downloaded, downloaded)
            }
        val notification =
            Notification
                .Builder(context, DONE_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_download)
                .setCategory(Notification.CATEGORY_STATUS)
                .setContentTitle(title)
                .setContentIntent(launchIntent())
                .setAutoCancel(true)
                .build()
        manager.notify(DONE_NOTIFICATION_ID, notification)
    }

    override suspend fun clear() {
        // Cancelled by id rather than `cancelAll`: the library-update notification lives in the same
        // shade and has nothing to do with a download run ending.
        context.notificationManager()?.cancel(PROGRESS_NOTIFICATION_ID)
    }

    /**
     * The ongoing notification as WorkManager wants it, for `setForeground`.
     *
     * Typed `dataSync` explicitly: from Android 14 a foreground service must declare what it is for,
     * and starting one without a type that matches the manifest throws. Downloading chapters the user
     * asked for is data sync in the platform's taxonomy — it is not a media, location, or
     * short-service case.
     *
     * @param progress what to show, or `null` before the first chapter has been resolved — the run has
     *   to become foreground *before* it knows what it is fetching, so there is always one frame with
     *   nothing to name.
     */
    suspend fun foregroundInfo(progress: DownloadProgress?): ForegroundInfo {
        context.notificationManager()?.ensureProgressChannel()
        val notification = progress?.toNotification() ?: startingNotification()
        return ForegroundInfo(
            PROGRESS_NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    /**
     * The series as the title, the chapter and its page count as the body, and a determinate bar when
     * the page count is known.
     *
     * `setOngoing` + `setOnlyAlertOnce`: it cannot be swiped away while the service holds it, and
     * updating it once per page must never re-alert. Without the second flag a forty-page chapter is
     * forty notification sounds.
     */
    private suspend fun DownloadProgress.toNotification(): Notification {
        val pageCount = download.pageCount
        val builder =
            Notification
                .Builder(context, PROGRESS_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_download)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setContentTitle(manga.title.value)
                .setContentText(bodyText())
                .setContentIntent(launchIntent())
                .setOngoing(true)
                .setOnlyAlertOnce(true)

        return if (pageCount != null && pageCount > 0) {
            builder.setProgress(pageCount, download.downloadedPages, false).build()
        } else {
            // Indeterminate rather than a zeroed bar: until the page list is fetched there is no total,
            // and a bar pinned at zero reads as "stuck" (the same reasoning as TanukiProgressRing).
            builder.setProgress(0, 0, true).build()
        }
    }

    /**
     * "Chapter 4 · 7 of 20 pages · 3 remaining", dropping the parts that are not known yet.
     *
     * The chapter is named the way the app names it everywhere else — the number when the source gave
     * one, else its own title — so the notification and the queue screen agree.
     */
    private suspend fun DownloadProgress.bodyText(): String {
        val parts = mutableListOf<String>()
        val label = chapter.chapterNumber?.let { getString(Res.string.notification_downloads_chapter, it.display) }
        (label ?: chapter.name?.value)?.let(parts::add)
        download.pageCount?.let {
            parts += getString(Res.string.notification_downloads_pages, download.downloadedPages, it)
        }
        if (remaining > 0) {
            parts += getPluralString(Res.plurals.notification_downloads_remaining, remaining, remaining)
        }
        return parts.joinToString(BODY_SEPARATOR)
    }

    /** Shown for the moment between the service starting and the first chapter being resolved. */
    private suspend fun startingNotification(): Notification =
        Notification
            .Builder(context, PROGRESS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setContentTitle(getString(Res.string.notification_downloads_title))
            .setContentText(getString(Res.string.notification_downloads_starting))
            .setContentIntent(launchIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .build()

    private fun Context.notificationManager(): NotificationManager? {
        val manager = getSystemService(NotificationManager::class.java) ?: return null
        if (!manager.areNotificationsEnabled()) {
            logger.info(TAG, "Notifications are disabled; the queue runs without them")
            return null
        }
        return manager
    }

    // Both channels are cheap to re-create: the platform ignores one that already exists and never
    // overwrites the user's own changes to it.
    private suspend fun NotificationManager.ensureProgressChannel() {
        createNotificationChannel(
            NotificationChannel(
                PROGRESS_CHANNEL_ID,
                getString(Res.string.notification_channel_downloads),
                // LOW: a status line, not an event. A chapter landing is not worth a sound.
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(Res.string.notification_channel_downloads_description)
                setShowBadge(false)
            },
        )
    }

    private suspend fun NotificationManager.ensureDoneChannel() {
        createNotificationChannel(
            NotificationChannel(
                DONE_CHANNEL_ID,
                getString(Res.string.notification_channel_downloads_done),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = getString(Res.string.notification_channel_downloads_done_description) },
        )
    }

    /**
     * Opens the app where the launcher would.
     *
     * Not a deep link to the queue screen: destinations live in `:core:navigation` and the host that
     * renders them is a single Activity this module cannot name — the same limitation
     * `AndroidLibraryUpdateNotifier` documents, and the same natural next step.
     */
    private fun launchIntent(): PendingIntent? {
        val intent =
            context.packageManager
                .getLaunchIntentForPackage(context.packageName)
                ?.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }
                ?: return null
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private companion object {
        const val TAG = "DownloadNotifier"

        const val PROGRESS_CHANNEL_ID = "downloads_progress"
        const val DONE_CHANNEL_ID = "downloads_done"

        /** Fixed: every update replaces the ongoing notification rather than adding to a pile. */
        const val PROGRESS_NOTIFICATION_ID = 2

        /** Distinct from the progress one, so clearing the run does not take the summary with it. */
        const val DONE_NOTIFICATION_ID = 3

        const val BODY_SEPARATOR = " · "
    }
}