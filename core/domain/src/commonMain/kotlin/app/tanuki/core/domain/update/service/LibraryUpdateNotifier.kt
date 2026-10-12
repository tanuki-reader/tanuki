package app.tanuki.core.domain.update.service

import app.tanuki.core.domain.update.model.MangaUpdateSummary

/**
 * Tells the user that chapters arrived, however the platform does that.
 *
 * Bound per platform (`NotificationManagerCompat` on Android, `UNUserNotificationCenter` on iOS) and
 * kept free of platform types in both directions, so nothing above it knows which one it got.
 *
 * **Permission is the implementation's problem, not this interface's.** Android must ask through an
 * Activity and iOS through its own prompt — plumbing that difference into a shared `requestPermission()`
 * would leak an Activity into the domain to serve one of the two. Instead, each implementation checks
 * its own authorization and does nothing when it doesn't have it, so a caller can always just call.
 */
interface LibraryUpdateNotifier {
    /**
     * Announces the given updates as a **single** notification, not one per series: a library that has
     * been away for a week can produce a dozen summaries, and a dozen notifications is spam.
     *
     * @param summaries The series that gained chapters; an empty list is a no-op. Ordering is preserved
     *   — callers pass the most recently updated first.
     */
    suspend fun notifyNewChapters(summaries: List<MangaUpdateSummary>)

    /**
     * Announces that repos published newer versions of installed sources, as one notification that a
     * later call replaces.
     *
     * @param sourceNames The sources with a new version, by display name; an empty list is a no-op.
     */
    suspend fun notifySourceUpdates(sourceNames: List<String>)
}