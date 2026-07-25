package app.tanuki.core.data.repository.settings

import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

/**
 * iOS [PreferencesFileFactory]: stores the preferences file in the app sandbox's Documents directory,
 * which is backed up and persists across launches (mirrors the database's on-disk location).
 */
actual class PreferencesFileFactory {
    actual fun resolve(): String = documentDirectory() + "/" + PREFERENCES_FILE_NAME

    @OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
    private fun documentDirectory(): String {
        val documentDirectory =
            NSFileManager.defaultManager.URLForDirectory(
                directory = NSDocumentDirectory,
                inDomain = NSUserDomainMask,
                appropriateForURL = null,
                create = false,
                error = null,
            )
        return requireNotNull(documentDirectory?.path)
    }
}