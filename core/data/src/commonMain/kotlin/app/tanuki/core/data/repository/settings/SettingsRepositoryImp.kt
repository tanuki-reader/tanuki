package app.tanuki.core.data.repository.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.MutablePreferences
import app.tanuki.core.domain.common.model.TriState
import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.library.model.CategoryMatchMode
import app.tanuki.core.domain.library.model.LibraryFilter
import app.tanuki.core.domain.library.model.LibraryPreferences
import app.tanuki.core.domain.library.model.LibrarySort
import app.tanuki.core.domain.manga.model.ReadingStatus
import app.tanuki.core.domain.settings.model.AutoDownloadMode
import app.tanuki.core.domain.settings.model.DeleteAfterReading
import app.tanuki.core.domain.settings.model.LibraryGridSize
import app.tanuki.core.domain.settings.model.ReaderBackground
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
import okio.Path
import okio.Path.Companion.toPath
import kotlin.time.Instant
import kotlin.uuid.Uuid

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
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class SettingsRepositoryImp internal constructor(
    preferencesPath: () -> Path,
    private val dispatcherProvider: DispatcherProvider
) : SettingsRepository {
    // The DI entry point. The primary constructor takes the path directly so tests can point the store at
    // a temporary file: [PreferencesFileFactory] is an expect class they can't build.
    @Inject
    constructor(
        fileFactory: PreferencesFileFactory,
        dispatcherProvider: DispatcherProvider
    ) : this({ fileFactory.resolve().toPath() }, dispatcherProvider)

    private val dataStore: DataStore<Preferences> =
        PreferenceDataStoreFactory.createWithPath(produceFile = preferencesPath)

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

    // Absent reads as `false`, and so does a corrupt store (see [preferences], which falls back to
    // empty). Both paths therefore mean "has not opted in", which is the only safe reading: the failure
    // mode of guessing wrong here is collecting diagnostics from someone who never agreed.
    override fun observeCrashReportingEnabled(): Flow<Boolean> =
        preferences.map { it[CRASH_REPORTING_KEY] ?: false }

    override suspend fun setCrashReportingEnabled(enabled: Boolean) {
        withContext(dispatcherProvider.io) {
            dataStore.edit { preferences -> preferences[CRASH_REPORTING_KEY] = enabled }
        }
    }

    // Created inside `edit`, which is atomic, so two first reads racing each other still settle on one id.
    override suspend fun getOrCreateInstallId(): String =
        withContext(dispatcherProvider.io) {
            val stored =
                dataStore.edit { preferences ->
                    if (preferences[INSTALL_ID_KEY] == null) preferences[INSTALL_ID_KEY] = Uuid.random().toString()
                }
            checkNotNull(stored[INSTALL_ID_KEY]) { "The install id was not written" }
        }

    override suspend fun resetInstallId(): String =
        withContext(dispatcherProvider.io) {
            val newId = Uuid.random().toString()
            dataStore.edit { preferences -> preferences[INSTALL_ID_KEY] = newId }
            newId
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

    // Absent reads as `false` — not paused. The other default would give a store that failed to load a
    // queue that silently never runs, with nothing on screen to explain it.
    override fun observeDownloadsPaused(): Flow<Boolean> = preferences.map { it[DOWNLOADS_PAUSED_KEY] ?: false }

    override suspend fun setDownloadsPaused(paused: Boolean) {
        withContext(dispatcherProvider.io) {
            dataStore.edit { preferences -> preferences[DOWNLOADS_PAUSED_KEY] = paused }
        }
    }

    override fun observeAutoDownloadMode(): Flow<AutoDownloadMode> =
        preferences.map { it[AUTO_DOWNLOAD_MODE_KEY].toAutoDownloadMode() }

    // Stored as canonical Uuid strings. An id whose category has since been deleted stays in the set and
    // is simply never matched — cheaper than reconciling this store against the database on every
    // category delete, and invisible either way.
    override fun observeAutoDownloadCategories(): Flow<Set<Uuid>> =
        preferences.map { stored ->
            stored[AUTO_DOWNLOAD_CATEGORIES_KEY].orEmpty().mapNotNullTo(mutableSetOf()) { it.toUuidOrNull() }
        }

    override suspend fun setAutoDownload(
        mode: AutoDownloadMode,
        categories: Set<Uuid>
    ) {
        withContext(dispatcherProvider.io) {
            // One edit, so a mode and the categories it refers to can never be half-written — a reader
            // between two separate writes would apply a rule the user never chose.
            dataStore.edit { preferences ->
                preferences[AUTO_DOWNLOAD_MODE_KEY] = mode.name
                preferences[AUTO_DOWNLOAD_CATEGORIES_KEY] = categories.mapTo(mutableSetOf()) { it.toString() }
            }
        }
    }

    override fun observeDownloadOnWifiOnly(): Flow<Boolean> = preferences.map { it[WIFI_ONLY_KEY] ?: false }

    override suspend fun setDownloadOnWifiOnly(wifiOnly: Boolean) {
        withContext(dispatcherProvider.io) {
            dataStore.edit { preferences -> preferences[WIFI_ONLY_KEY] = wifiOnly }
        }
    }

    override fun observeDeleteAfterReading(): Flow<DeleteAfterReading> =
        preferences.map { it[DELETE_AFTER_READING_KEY].toDeleteAfterReading() }

    override suspend fun setDeleteAfterReading(setting: DeleteAfterReading) {
        withContext(dispatcherProvider.io) {
            dataStore.edit { preferences -> preferences[DELETE_AFTER_READING_KEY] = setting.name }
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

    override fun observeLibraryPreferences(): Flow<LibraryPreferences> = preferences.map { it.toLibraryPreferences() }

    override suspend fun updateLibraryPreferences(transform: (LibraryPreferences) -> LibraryPreferences) {
        withContext(dispatcherProvider.io) {
            dataStore.edit { preferences -> preferences.putLibraryPreferences(transform(preferences.toLibraryPreferences())) }
        }
    }

    override fun observeLibraryGridSize(): Flow<LibraryGridSize> =
        preferences.map { it[LIBRARY_GRID_SIZE_KEY].toEnum<LibraryGridSize>() ?: LibraryGridSize.Medium }

    override suspend fun setLibraryGridSize(size: LibraryGridSize) {
        withContext(dispatcherProvider.io) {
            dataStore.edit { preferences -> preferences[LIBRARY_GRID_SIZE_KEY] = size.name }
        }
    }

    override fun observeReaderBackground(): Flow<ReaderBackground> =
        preferences.map { it[READER_BACKGROUND_KEY].toEnum<ReaderBackground>() ?: ReaderBackground.Black }

    override suspend fun setReaderBackground(background: ReaderBackground) {
        withContext(dispatcherProvider.io) {
            dataStore.edit { preferences -> preferences[READER_BACKGROUND_KEY] = background.name }
        }
    }

    // Absent or corrupt reads as `false`: guessing wrong the other way shows adult content unasked.
    override fun observeShowSensitiveContent(): Flow<Boolean> = preferences.map { it[SHOW_SENSITIVE_KEY] ?: false }

    override suspend fun setShowSensitiveContent(show: Boolean) {
        withContext(dispatcherProvider.io) {
            dataStore.edit { preferences -> preferences[SHOW_SENSITIVE_KEY] = show }
        }
    }

    private companion object {
        val CRASH_REPORTING_KEY = booleanPreferencesKey("crash_reporting_enabled")
        val INSTALL_ID_KEY = stringPreferencesKey("install_id")
        val THEME_KEY = stringPreferencesKey("theme_preference")
        val UPDATE_INTERVAL_KEY = stringPreferencesKey("library_update_interval")
        val LAST_UPDATE_KEY = longPreferencesKey("last_library_update")
        val REMOVED_SOURCES_KEY = stringSetPreferencesKey("removed_source_ids")
        val DOWNLOADS_PAUSED_KEY = booleanPreferencesKey("downloads_paused")
        val AUTO_DOWNLOAD_MODE_KEY = stringPreferencesKey("auto_download_mode")
        val AUTO_DOWNLOAD_CATEGORIES_KEY = stringSetPreferencesKey("auto_download_categories")
        val WIFI_ONLY_KEY = booleanPreferencesKey("download_on_wifi_only")
        val DELETE_AFTER_READING_KEY = stringPreferencesKey("delete_after_reading")
        val LIBRARY_READING_STATUS_KEY = stringPreferencesKey("library_filter_reading_status")
        val LIBRARY_PUBLICATION_KEY = stringPreferencesKey("library_filter_publication")
        val LIBRARY_DOWNLOADED_KEY = stringPreferencesKey("library_filter_downloaded")
        val LIBRARY_CATEGORIES_KEY = stringSetPreferencesKey("library_filter_categories")
        val LIBRARY_MATCH_MODE_KEY = stringPreferencesKey("library_filter_match_mode")
        val LIBRARY_SORT_MODE_KEY = stringPreferencesKey("library_sort_mode")
        val LIBRARY_SORT_DIRECTION_KEY = stringPreferencesKey("library_sort_direction")
        val LIBRARY_GRID_SIZE_KEY = stringPreferencesKey("library_grid_size")
        val READER_BACKGROUND_KEY = stringPreferencesKey("reader_background")
        val SHOW_SENSITIVE_KEY = booleanPreferencesKey("show_sensitive_content")

        /** Each field by enum name; anything absent or unknown reads as that field's default. */
        fun Preferences.toLibraryPreferences(): LibraryPreferences {
            val filter = LibraryFilter.None
            val sort = LibrarySort.Default
            return LibraryPreferences(
                filter =
                    LibraryFilter(
                        readingStatus = this[LIBRARY_READING_STATUS_KEY].toEnum<ReadingStatus>(),
                        publicationCompleted =
                            this[LIBRARY_PUBLICATION_KEY].toEnum<TriState>() ?: filter.publicationCompleted,
                        downloaded = this[LIBRARY_DOWNLOADED_KEY].toEnum<TriState>() ?: filter.downloaded,
                        categoryIds =
                            this[LIBRARY_CATEGORIES_KEY].orEmpty().mapNotNullTo(mutableSetOf()) { it.toUuidOrNull() },
                        categoryMatchMode =
                            this[LIBRARY_MATCH_MODE_KEY].toEnum<CategoryMatchMode>() ?: filter.categoryMatchMode
                    ),
                sort =
                    LibrarySort(
                        mode = this[LIBRARY_SORT_MODE_KEY].toEnum<LibrarySort.Mode>() ?: sort.mode,
                        direction = this[LIBRARY_SORT_DIRECTION_KEY].toEnum<LibrarySort.Direction>() ?: sort.direction
                    )
            )
        }

        fun MutablePreferences.putLibraryPreferences(value: LibraryPreferences) {
            val filter = value.filter
            val status = filter.readingStatus
            if (status == null) remove(LIBRARY_READING_STATUS_KEY) else this[LIBRARY_READING_STATUS_KEY] = status.name
            this[LIBRARY_PUBLICATION_KEY] = filter.publicationCompleted.name
            this[LIBRARY_DOWNLOADED_KEY] = filter.downloaded.name
            this[LIBRARY_CATEGORIES_KEY] = filter.categoryIds.mapTo(mutableSetOf()) { it.toString() }
            this[LIBRARY_MATCH_MODE_KEY] = filter.categoryMatchMode.name
            this[LIBRARY_SORT_MODE_KEY] = value.sort.mode.name
            this[LIBRARY_SORT_DIRECTION_KEY] = value.sort.direction.name
        }

        inline fun <reified E : Enum<E>> String?.toEnum(): E? = enumValues<E>().firstOrNull { it.name == this }

        /**
         * Maps a stored name back to an [AutoDownloadMode]. An unknown name — written by a newer build,
         * or a corrupt store — falls back to `Off`, which is the only safe direction: the failure mode
         * of guessing the other way is downloading over someone's mobile data unasked.
         */
        fun String?.toAutoDownloadMode(): AutoDownloadMode =
            AutoDownloadMode.entries.firstOrNull { it.name == this } ?: AutoDownloadMode.Off

        /** The same, for [DeleteAfterReading] — where the safe direction is "never delete anything". */
        fun String?.toDeleteAfterReading(): DeleteAfterReading =
            DeleteAfterReading.entries.firstOrNull { it.name == this } ?: DeleteAfterReading.Off

        /** A stored id that is not a Uuid is debris from a hand-edited store; drop it rather than throw. */
        fun String.toUuidOrNull(): Uuid? = runCatching { Uuid.parse(this) }.getOrNull()

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