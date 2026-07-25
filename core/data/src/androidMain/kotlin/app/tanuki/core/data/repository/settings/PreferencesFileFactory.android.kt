package app.tanuki.core.data.repository.settings

import android.content.Context

/**
 * Android [PreferencesFileFactory]: stores the preferences file in the app's private `files/` directory,
 * resolved from the application [Context].
 */
actual class PreferencesFileFactory(
    private val context: Context,
) {
    actual fun resolve(): String =
        context.applicationContext
            .filesDir
            .resolve(PREFERENCES_FILE_NAME)
            .absolutePath
}