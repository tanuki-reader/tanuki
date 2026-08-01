package app.tanuki.core.data.repository.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.settings.model.LibraryUpdateInterval
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okio.Path.Companion.toPath
import kotlin.time.Instant

/**
 * DataStore-backed [SettingsRepository]. App-level settings — the theme, the library update schedule,
 * and the set of sources the user has uninstalled — live in a single preferences store rather than the
 * relational database.
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

    /**
     * The stored preferences, with a read failure (missing/corrupt file) surfacing as defaults rather
     * than crashing whatever is observing. Every read below builds on this, so no single setting can
     * forget the fallback.
     */
    private val preferences: Flow<Preferences> =
        dataStore.data
            .catch { throwable ->
                if (throwable is CancellationException) throw throwable
                emit(emptyPreferences())
            }.flowOn(dispatcherProvider.io)

    override fun observeThemePreference(): Flow<ThemePreference> =
        preferences.map { it[THEME_KEY].toThemePreference() }

    override suspend fun setThemePreference(preference: ThemePreference) {
        withContext(dispatcherProvider.io) {
            dataStore.edit { preferences -> preferences[THEME_KEY] = preference.name }
        }
    }

    override fun observeLibraryUpdateInterval(): Flow<LibraryUpdateInterval> =
        preferences.map { it[UPDATE_INTERVAL_KEY].toUpdateInterval() }

    override suspend fun setLibraryUpdateInterval(interval: LibraryUpdateInterval) {
        withContext(dispatcherProvider.io) {
            dataStore.edit { preferences -> preferences[UPDATE_INTERVAL_KEY] = interval.name }
        }
    }

    override suspend fun getLastLibraryUpdate(): Instant? =
        preferences
            .first()[LAST_UPDATE_KEY]
            ?.let(Instant::fromEpochMilliseconds)

    override suspend fun setLastLibraryUpdate(at: Instant) {
        withContext(dispatcherProvider.io) {
            dataStore.edit { preferences -> preferences[LAST_UPDATE_KEY] = at.toEpochMilliseconds() }
        }
    }

    override suspend fun getRemovedSourceIds(): Set<String> = preferences.first()[REMOVED_SOURCES_KEY].orEmpty()

    override suspend fun setSourceRemoved(
        sourceId: String,
        removed: Boolean
    ) {
        withContext(dispatcherProvider.io) {
            dataStore.edit { preferences ->
                val current = preferences[REMOVED_SOURCES_KEY].orEmpty()
                // Rebuilt rather than mutated: DataStore hands back the stored set, and editing it in
                // place would mutate the very value the next read is served from.
                preferences[REMOVED_SOURCES_KEY] = if (removed) current + sourceId else current - sourceId
            }
        }
    }

    private companion object {
        val THEME_KEY = stringPreferencesKey("theme_preference")
        val UPDATE_INTERVAL_KEY = stringPreferencesKey("library_update_interval")
        val LAST_UPDATE_KEY = longPreferencesKey("last_library_update")
        val REMOVED_SOURCES_KEY = stringSetPreferencesKey("removed_source_ids")

        /** Maps a stored name back to a [ThemePreference], defaulting to [ThemePreference.System]. */
        fun String?.toThemePreference(): ThemePreference =
            ThemePreference.entries.firstOrNull { it.name == this } ?: ThemePreference.System

        /**
         * Maps a stored name back to a [LibraryUpdateInterval]. An unknown name — a value written by a
         * newer build, or a corrupt store — falls back to the default rather than to `OFF`: silently
         * never checking for chapters again is a far worse failure than checking on the usual schedule.
         */
        fun String?.toUpdateInterval(): LibraryUpdateInterval =
            LibraryUpdateInterval.entries.firstOrNull { it.name == this } ?: LibraryUpdateInterval.Default
    }
}