package app.tanuki.core.data.repository.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.settings.model.ThemePreference
import app.tanuki.core.domain.settings.repository.SettingsRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okio.Path.Companion.toPath

/**
 * DataStore-backed [SettingsRepository]. App-level scalars (currently just the theme) live in a single
 * preferences store rather than the relational database.
 *
 * The [DataStore] is built and owned here rather than provided as a Metro binding, so the third-party
 * `DataStore`/`Preferences` types never cross the DI boundary — only the app-owned [PreferencesFileFactory]
 * does. Being [SingleIn] the app scope, exactly one instance (hence one DataStore for the file) exists;
 * constructing a second DataStore for the same file would throw.
 *
 * The theme is persisted by its enum [ThemePreference.name]; an unknown/absent value falls back to
 * [ThemePreference.System], so a corrupt or older store degrades gracefully instead of throwing.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class SettingsRepositoryImp(
    fileFactory: PreferencesFileFactory,
    private val dispatcherProvider: DispatcherProvider
) : SettingsRepository {
    private val dataStore: DataStore<Preferences> =
        PreferenceDataStoreFactory.createWithPath(produceFile = { fileFactory.resolve().toPath() })

    override fun observeThemePreference(): Flow<ThemePreference> =
        dataStore.data
            // A read failure (missing/corrupt file) should surface as defaults, not crash the UI.
            .catch { throwable ->
                if (throwable is CancellationException) throw throwable
                emit(emptyPreferences())
            }.map { preferences -> preferences[THEME_KEY].toThemePreference() }
            .flowOn(dispatcherProvider.io)

    override suspend fun setThemePreference(preference: ThemePreference) {
        withContext(dispatcherProvider.io) {
            dataStore.edit { preferences -> preferences[THEME_KEY] = preference.name }
        }
    }

    private companion object {
        val THEME_KEY = stringPreferencesKey("theme_preference")

        /** Maps a stored name back to a [ThemePreference], defaulting to [ThemePreference.System]. */
        fun String?.toThemePreference(): ThemePreference =
            ThemePreference.entries.firstOrNull { it.name == this } ?: ThemePreference.System
    }
}