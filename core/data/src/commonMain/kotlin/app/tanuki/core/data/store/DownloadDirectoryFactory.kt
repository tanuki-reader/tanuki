package app.tanuki.core.data.store

/**
 * Resolves the absolute on-disk root that downloaded chapters live under, per platform (mirrors
 * [app.tanuki.core.data.repository.settings.PreferencesFileFactory] for preferences and
 * [app.tanuki.core.data.database.DatabaseBuilderFactory] for the database).
 *
 * Two constraints both platforms have to honour, and neither is negotiable:
 *
 *  - **Never a cache directory.** The OS reclaims those under storage pressure, and a chapter the user
 *    explicitly asked to keep is user data, not a cache. This is the difference between "the app saved
 *    space for me" and "the app lost my offline library on the flight".
 *  - **Excluded from cloud backup.** A downloaded library is gigabytes of bytes that can be re-fetched
 *    for free; uploading them is a cost to the user, to the source, and to the app's review standing.
 *    Nothing on Android's internal storage is auto-backed-up at this size, but iOS backs up its
 *    Application Support directory by default, so the actual has to opt out explicitly.
 */
expect class DownloadDirectoryFactory {
    /** The downloads root, created if it does not exist yet. */
    fun resolve(): String
}

/** Directory name under the platform's app-internal storage. */
internal const val DOWNLOADS_DIRECTORY_NAME = "downloads"
