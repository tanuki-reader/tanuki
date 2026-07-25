package app.tanuki.core.data.repository.settings

/**
 * Resolves the absolute on-disk path of the app's DataStore preferences file, per platform (mirrors
 * [app.tanuki.core.data.database.DatabaseBuilderFactory] for the database). The path must end in
 * `.preferences_pb` — the extension [androidx.datastore.preferences.core.PreferenceDataStoreFactory]
 * requires.
 */
expect class PreferencesFileFactory {
    fun resolve(): String
}

/** DataStore preferences filename. The `.preferences_pb` suffix is required by the preferences factory. */
internal const val PREFERENCES_FILE_NAME = "tanuki.preferences_pb"