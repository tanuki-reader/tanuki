package app.tanuki.core.data.store

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDomainMask

/**
 * iOS [DownloadDirectoryFactory]: `<Application Support>/downloads` — app-internal, persists across
 * launches, and not something the system reclaims (unlike Caches). The same place installed `.wasm`
 * bundles live.
 *
 * The directory is created here rather than lazily by the store, because it has to exist before
 * `isExcludedFromBackup` can be set on it — and that flag is the point. Without it a multi-gigabyte
 * offline library is uploaded to the user's iCloud backup, burning their storage on images that any
 * source will hand back for free, which is both a bad deal for the user and the kind of thing App
 * Review rejects builds over.
 */
@OptIn(ExperimentalForeignApi::class)
actual class DownloadDirectoryFactory {
    actual fun resolve(): String {
        val fileManager = NSFileManager.defaultManager
        val supportUrl =
            fileManager.URLForDirectory(
                directory = NSApplicationSupportDirectory,
                inDomain = NSUserDomainMask,
                appropriateForURL = null,
                create = true,
                error = null,
            )
        val root =
            requireNotNull(supportUrl) { "Could not resolve iOS Application Support directory" }
                .URLByAppendingPathComponent(DOWNLOADS_DIRECTORY_NAME)
        val directory = requireNotNull(root) { "Could not build the downloads directory URL" }

        fileManager.createDirectoryAtURL(directory, withIntermediateDirectories = true, attributes = null, error = null)
        directory.excludeFromBackup()

        return requireNotNull(directory.path) { "Downloads directory URL has no file path" }
    }

    /**
     * Marks the directory as not-to-be-backed-up. Applied on every resolve rather than only at
     * creation: the flag lives in the file's extended attributes, so restoring the app from a backup
     * made before this existed — or any other route that recreates the directory — would otherwise
     * leave it silently included again.
     *
     * Failures are ignored. The flag is an optimization, and an app that refused to start because it
     * could not set an extended attribute would be trading a large problem for a total one.
     */
    private fun NSURL.excludeFromBackup() {
        setResourceValue(value = true, forKey = NSURLIsExcludedFromBackupKey, error = null)
    }
}
