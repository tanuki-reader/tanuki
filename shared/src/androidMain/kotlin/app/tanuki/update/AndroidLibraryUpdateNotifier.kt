package app.tanuki.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.domain.common.service.info
import app.tanuki.core.domain.update.model.MangaUpdateSummary
import app.tanuki.core.domain.update.service.LibraryUpdateNotifier
import app.tanuki.shared.R
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Android [LibraryUpdateNotifier], posting one grouped notification.
 *
 * Built on the platform [NotificationManager] rather than the AndroidX compat wrappers: at this app's
 * `minSdk` every API here (channels, `Notification.Builder`, inbox style) has been on the platform for
 * many releases, so the compat layer would add a dependency to paper over versions the app cannot run
 * on anyway.
 *
 * Copy is resolved from the app's resources through [Context], which follows the system locale — the
 * right source here, since a notification is read outside the app and there is no composition to
 * inherit a locale from.
 *
 * Permission is checked, never requested: `POST_NOTIFICATIONS` needs an Activity to prompt from and
 * this runs from a background worker. `MainActivity` asks; if the user declined, this quietly does
 * nothing rather than failing the caller's run.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class AndroidLibraryUpdateNotifier(
    private val context: Context,
    private val logger: Logger
) : LibraryUpdateNotifier {
    override suspend fun notifyNewChapters(summaries: List<MangaUpdateSummary>) {
        if (summaries.isEmpty()) return

        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!manager.areNotificationsEnabled()) {
            logger.info(TAG, "Notifications are disabled; skipping ${summaries.size} update(s)")
            return
        }
        manager.ensureChannel()

        val notification =
            Notification
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_library_update)
                .setCategory(Notification.CATEGORY_RECOMMENDATION)
                .setContentIntent(launchIntent())
                .setAutoCancel(true)
                .applySummaries(summaries)
                .build()

        // A fixed id, so a later run replaces this notification rather than stacking beside it: it
        // always says what is currently waiting, not what one particular run happened to find.
        manager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Fills in the title and body. One series speaks for itself ("One Piece" / "3 new chapters");
     * several are counted in the title and listed in an expandable inbox style, the only layout in
     * which more than two lines stay readable in the shade.
     */
    private suspend fun Notification.Builder.applySummaries(
        summaries: List<MangaUpdateSummary>
    ): Notification.Builder {
        val single = summaries.singleOrNull()
        if (single != null) {
            return setContentTitle(single.title).setContentText(single.chapterCountText())
        }

        val lines = summaries.map { context.getString(R.string.notification_updates_line, it.title, it.chapterCountText()) }
        val inbox = Notification.InboxStyle()
        lines.forEach(inbox::addLine)
        return setContentTitle(context.resources.getQuantityString(R.plurals.notification_updates_title, summaries.size, summaries.size))
            .setContentText(lines.joinToString(SUMMARY_SEPARATOR))
            .setStyle(inbox)
    }

    private suspend fun MangaUpdateSummary.chapterCountText(): String =
        context.resources.getQuantityString(R.plurals.notification_updates_chapters, newChapterCount, newChapterCount)

    private suspend fun NotificationManager.ensureChannel() {
        // Cheap to re-create: the platform ignores a channel that already exists and never overwrites
        // the user's own changes to it. So there is nothing to track and nothing to migrate.
        createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_updates),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.notification_channel_updates_description) },
        )
    }

    /**
     * Opens the app where the launcher would.
     *
     * Not a deep link to the updated series: destinations live in `:core:navigation` and the host that
     * renders them is a single Activity this module cannot name, so a tap lands on Home — where the
     * "New Chapters" list is anyway. Carrying a destination through the intent is the natural next step.
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
        const val TAG = "LibraryUpdateNotifier"
        const val CHANNEL_ID = "library_updates"

        /** Fixed, so each run replaces the previous notification instead of adding to a pile. */
        const val NOTIFICATION_ID = 1

        /** Joins the collapsed one-line preview of a multi-series notification. */
        const val SUMMARY_SEPARATOR = ", "
    }
}
